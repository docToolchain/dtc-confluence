package org.docToolchain.atlassian.confluence.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.docToolchain.atlassian.confluence.image.EmbeddedDiagram;
import org.docToolchain.atlassian.confluence.image.EmbeddedImage;
import org.docToolchain.atlassian.confluence.image.ImageStore;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;

/**
 * Turns the images of a document into what Confluence understands.
 *
 * <p>An image on this machine becomes an attachment of the page and is referred to by name; one on
 * another host stays a reference to that host. The difference matters: an attachment survives the
 * page being read by someone who cannot reach the original server, and a reference does not.</p>
 *
 * <p>An image embedded in the document as a data URI has no file to attach, so it is written to
 * disk first, named after the hash of its content.</p>
 *
 * <p>An image whose file carries a draw.io diagram inside it becomes the draw.io macro instead,
 * with the diagram attached beside the picture under a name of its own. An image that carries no
 * diagram is left exactly as it was - that is the whole rule, and it is what lets one
 * {@code image::x.png[...]} mean a picture in one document and an editable diagram in the next
 * without the document saying which.</p>
 */
public class ImageTransformer {

    /** What Confluence uses when the document says nothing. */
    private static final String DEFAULT_WIDTH = "500";

    private static final String DEFAULT_ALIGNMENT = "center";

    /**
     * What the version comment of the diagram says. The content hash is appended to it, so a
     * later publish still recognises the revision as one of ours; the draw.io app's own comment
     * for the same file is {@code draw.io diagram} and carries no hash.
     */
    private static final String DIAGRAM_COMMENT = "the draw.io diagram this page's macro shows";

    /** And of the picture the app shows before its editor has run. */
    private static final String PREVIEW_COMMENT = "the preview of the draw.io diagram";

    private final String baseUrl;
    private final List<String> imageDirs;

    /**
     * @param baseUrl   the HTML file the images are relative to
     * @param imageDirs where to look for an image, and where to write an embedded one
     */
    /** Whether an embedded image that is not on disk yet is written there. */
    private final boolean writing;

    /** What the page already holds, for a name the export could not write as it was. */
    private final AttachmentNameLookup attachmentNames;

    public ImageTransformer(String baseUrl, List<String> imageDirs) {
        this(baseUrl, imageDirs, true);
    }

    /**
     * @param writing false for a dry run, which needs the names of the embedded images to build
     *                the body it compares and must not leave the files behind
     */
    public ImageTransformer(String baseUrl, List<String> imageDirs, boolean writing) {
        this(baseUrl, imageDirs, writing, AttachmentNameLookup.NONE);
    }

    /**
     * @param attachmentNames the attachments of the page this body goes onto, which a reference
     *                        the export sanitised is matched against
     */
    public ImageTransformer(String baseUrl, List<String> imageDirs, boolean writing,
                            AttachmentNameLookup attachmentNames) {
        this.baseUrl = baseUrl;
        this.imageDirs = imageDirs;
        this.writing = writing;
        this.attachmentNames = attachmentNames;
    }

    /**
     * @return the files that have to be attached once the page exists
     */
    public List<Upload> transformImages(Element body) {
        List<Upload> uploads = new ArrayList<>();
        Elements images = body.select("img");
        Set<String> merging = namesTwoFilesWouldShare(images);
        // Per page, because an attachment is per page: two pictures of one page carrying two
        // different diagrams under the same name are a collision, and two pages are not.
        Map<String, Diagram> diagrams = new LinkedHashMap<>();
        for (Element image : images) {
            String source = image.attr("src");
            String width = valueOr(image.attr("width"), DEFAULT_WIDTH);
            String alignment = valueOr(image.attr("align"), DEFAULT_ALIGNMENT);

            if (source.startsWith("http")) {
                // Nothing to attach: it stays a reference, and shows only while that host serves it.
                image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                        + "\"><ri:url ri:value=\"" + source + "\"/></ac:image>");
            } else {
                attach(image, source, width, alignment, merging, uploads, diagrams);
            }
            image.remove();
        }
        return uploads;
    }

    /**
     * The attachment names that two different files of this page would end up sharing.
     *
     * <p>Taking the version off a name can merge two pictures into one: a page showing
     * {@code 1_diagram.png} beside {@code 2_diagram.png} - which is what the prefix is there for -
     * would attach both as {@code diagram.png}, and both positions would then show whichever was
     * uploaded last. An author numbering their screenshots {@code 01_step.png},
     * {@code 02_step.png} is the same case and the more common one.</p>
     *
     * <p>Decided over the whole body before anything is written, because it is a property of the
     * page rather than of one image, and a name is only safe to shorten once every other name on
     * the page is known - the names a conversion is about to invent among them, which
     * {@link #namesClaimedBy} predicts because they do not exist yet.</p>
     *
     * @return the names that more than one file of this page lays claim to
     */
    private Set<String> namesTwoFilesWouldShare(Elements images) {
        Map<String, Set<String>> filesByName = new LinkedHashMap<>();
        for (Element image : images) {
            String source = image.attr("src");
            if (source.startsWith("http") || source.startsWith("data:image")) {
                continue;
            }
            for (String name : namesClaimedBy(source)) {
                filesByName
                        .computeIfAbsent(name, claimed -> new LinkedHashSet<>())
                        .add(SourcePath.decode(source));
            }
        }
        Set<String> merging = new LinkedHashSet<>();
        filesByName.forEach((name, files) -> {
            if (files.size() > 1) {
                merging.add(name);
                report(name, files);
            }
        });
        return merging;
    }

    /**
     * Every attachment name one file lays claim to: the name it is attached under, and where it
     * carries a diagram the diagram's own name and its preview's as well.
     *
     * <p>Those last two are derived from the file's name and so do not exist while the page is
     * being planned. Left out of it, the preview of {@code 1_arch.svg} and an ordinary
     * {@code 2_arch.png} were both scheduled as {@code arch.png} and the second upload replaced
     * the first - one of the two positions then showed the other's picture.</p>
     *
     * <p>Predicted by the rule {@link #asDiagram} applies, at the shortened name, which is what
     * the file is attached as while nothing clashes. Where its own name does clash it keeps the
     * long one instead and claims names of its own, so a prediction can only be over-careful
     * here: another file keeps a name it could have shortened.</p>
     */
    private List<String> namesClaimedBy(String source) {
        String fileName = AttachmentName.of(lastSegmentOf(source)).fileName();
        String url = SourcePath.decode(documentDirectory() + source);
        byte[] picture = readOrNull(url);
        String previewUrl = previewUrlOf(url);
        if (EmbeddedDiagram.of(picture).isEmpty()
                || (!previewUrl.equals(url) && readOrNull(previewUrl) == null)) {
            return List.of(fileName);
        }
        String name = withoutExtension(fileName);
        return List.of(fileName, name, name + ".png");
    }

    /**
     * Says which files are affected and what happens to them, because the alternative - picking
     * one of them and attaching it twice - is a document that shows the wrong picture and says
     * nothing about it.
     */
    private static void report(String name, Set<String> files) {
        Set<String> kept = new LinkedHashSet<>();
        files.forEach(file -> kept.add(segmentOf(file)));
        System.out.println(">>> WARN: more than one file would be attached as '" + name + "': "
                + String.join(", ", files) + ". Confluence holds one attachment per name.");
        System.out.println(kept.size() > 1
                ? "    They are attached under the names the document refers to instead, so "
                        + "nothing is merged that the document tells apart. A diagram an app has "
                        + "to find under its own name needs a name of its own."
                : "    The document refers to them all by that one name, so the page will show "
                        + "whichever is uploaded last.");
    }

    private void attach(Element image, String source, String width, String alignment,
                        Set<String> merging, List<Upload> uploads,
                        Map<String, Diagram> diagrams) {
        String directory = documentDirectory();
        String url;
        String fileName;
        int exportedVersion = AttachmentName.NO_VERSION;
        // The revision of the diagram the picture carries, which is not the picture's own: they
        // are two attachments and Confluence writes them apart.
        int diagramVersion = AttachmentName.NO_VERSION;
        // Whether the document named the file. A diagram is attached under the picture's own
        // name, and an embedded image has none of its own: the store names it after the hash of
        // its content, so every edit of the diagram would attach it under a new name, leave the
        // one before it behind, and move the macro to a diagram nobody can find twice.
        boolean namedByTheDocument = !source.startsWith("data:image");
        if (namedByTheDocument) {
            url = directory + source;
            // The file on disk keeps the version an export wrote into its name; the attachment
            // does not. Confluence knows the diagram under its own name, and the app that drew it
            // looks for exactly that name.
            String segment = lastSegmentOf(source);
            AttachmentName attachment = AttachmentName.of(segment);
            if (merging.contains(attachment.fileName())) {
                // Shortening this one would merge it with another file of this page. The name the
                // document refers to is kept, and with it the version is dropped: the revision we
                // exported from belongs to an attachment of the shortened name, and comparing it
                // against a different attachment's revision could authorise a write over
                // somebody else's file.
                fileName = segment;
            } else {
                fileName = attachment.fileName();
                exportedVersion = attachment.exportedVersion();
                diagramVersion = attachment.exportedDiagramVersion();
            }
        } else {
            EmbeddedImage embedded = EmbeddedImage.parse(source);
            String extension = embedded.fileExtension();
            String named = image.attr("alt").replaceAll("\\s+", "_") + "." + extension;
            ImageStore store = writing ? new ImageStore(imageDirs) : ImageStore.readOnly(imageDirs);
            ImageStore.StoredImage stored =
                    store.store(directory, named, extension, embedded.content());
            url = stored.filePath();
            fileName = stored.fileName();
        }
        url = SourcePath.decode(url);
        System.out.println("    image: " + url);
        // Here rather than over the finished body: the body refers to the attachment by name, so
        // the name has to be the one Confluence holds before it is written down - and the upload
        // has to go up under the same name, or the page points at a file nobody attached.
        fileName = attachmentNames.resolve(fileName);
        if (namedByTheDocument && asDiagram(image, url, fileName, width, exportedVersion,
                diagramVersion, uploads, diagrams)) {
            return;
        }
        // Escaped, because the fragment is parsed again: a quote in the name - the data-URI
        // branch takes it from the alt text - would close the attribute and cut the name short,
        // leaving the page pointing at an attachment nobody uploads.
        image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                + "\"><ri:attachment ri:filename=\"" + Entities.escape(fileName)
                + "\"/></ac:image>");
        uploads.add(new Upload(url, fileName, "automatically uploaded", exportedVersion));
    }

    /**
     * Writes the draw.io macro in place of the picture, where the picture carries a diagram.
     *
     * <p>Two attachments for one file in the repository: the diagram under the picture's base name
     * with no extension at all, which is the one the app binds to, and the picture as
     * {@code <name>.png}, which is what the page shows before the editor's JavaScript has run and
     * what an export to PDF gets.</p>
     *
     * <p>Each of the two carries the revision of its own attachment, which is what the clobber
     * guard compares. Handed one revision for both, a publish right after the export refused the
     * preview it had not touched - Confluence held the preview's revision, not the diagram's - and
     * would have passed a preview somebody else had written up to that number.</p>
     *
     * @return whether this image became a diagram, so that the caller leaves the picture alone
     */
    private boolean asDiagram(Element image, String url, String fileName, String width,
                              int exportedVersion, int diagramVersion, List<Upload> uploads,
                              Map<String, Diagram> diagrams) {
        byte[] picture = readOrNull(url);
        Optional<String> found = EmbeddedDiagram.of(picture);
        if (found.isEmpty()) {
            return false;
        }
        String diagram = found.get();
        String name = withoutExtension(fileName);
        Diagram alreadyThere = diagrams.get(name);
        if (alreadyThere != null) {
            return shownAgain(image, fileName, name, width, alreadyThere, diagram);
        }

        // An SVG carrying a diagram still needs a PNG to be its preview, because that is the name
        // the app asks for. The PNG beside it, under the same name, is the only candidate that
        // needs neither a renderer nor a guess - and a PNG carrying a diagram is its own preview.
        String previewUrl = previewUrlOf(url);
        byte[] preview = previewUrl.equals(url) ? picture : readOrNull(previewUrl);
        if (preview == null) {
            System.out.println(">>> WARN: " + fileName + " carries a draw.io diagram, but there is"
                    + " no " + name + ".png beside it to be the preview the app shows before its"
                    + " editor has run - and an export to PDF has nothing else to show. It stays a"
                    + " picture.");
            return false;
        }

        // The preview's shape travels with the diagram, so that a second macro on it is sized
        // from the same picture - at its own width, which need not be this one's.
        Diagram shape = new Diagram(diagram, EmbeddedDiagram.sizeOfPng(preview).orElse(null));
        diagrams.put(name, shape);
        String height = shape.heightAt(width);
        uploads.add(new Upload(url, name, DIAGRAM_COMMENT, diagramVersion,
                diagram.getBytes(StandardCharsets.UTF_8)));
        uploads.add(new Upload(previewUrl, name + ".png", PREVIEW_COMMENT, exportedVersion));
        image.after(DrawioMacro.showing(name, width, height));
        System.out.println("    diagram: " + fileName + " carries one, published as the editable"
                + " diagram '" + name + "' with " + name + ".png as its preview");
        return true;
    }

    /**
     * What to do about a second picture of this page wanting the same diagram name.
     *
     * <p>The same diagram twice is one attachment and two macros on it, which is the arrangement
     * Confluence itself writes where one diagram appears in several places on a page. Two
     * different diagrams under one name is a collision over a single attachment: whichever upload
     * went last would decide what both macros show, so the second stays a picture and the run
     * says which two files are in each other's way.</p>
     */
    private static boolean shownAgain(Element image, String fileName, String name, String width,
                                      Diagram alreadyThere, String diagram) {
        if (!alreadyThere.source().equals(diagram)) {
            System.out.println(">>> WARN: " + fileName + " carries a draw.io diagram that would be"
                    + " attached as '" + name + "', and another picture of this page has already"
                    + " put a different diagram there. Confluence holds one attachment of that"
                    + " name, so this one stays a picture; rename one of the two files to publish"
                    + " both as diagrams.");
            return false;
        }
        image.after(DrawioMacro.showing(name, width, alreadyThere.heightAt(width)));
        System.out.println("    diagram: " + fileName + " shows '" + name + "' again");
        return true;
    }

    /** @return where the document's images are, which is where the document itself is */
    private String documentDirectory() {
        return baseUrl.replaceAll("\\\\", "/").replaceAll("/[^/]*$", "/");
    }

    /** @return the picture the app shows before its editor has run, which it asks for by name */
    private static String previewUrlOf(String url) {
        return withoutExtension(url) + ".png";
    }

    /**
     * @return the bytes of the file, or {@code null} where there is no readable file there. A
     *         picture the document names and the repository does not hold is a case this already
     *         has - the upload is what reports it - so looking for a diagram must not end the run.
     */
    private static byte[] readOrNull(String url) {
        try {
            return Files.readAllBytes(Path.of(url));
        } catch (IOException | RuntimeException notThere) {
            return null;
        }
    }

    /**
     * @return the name without its last extension, or the name itself where it has none. Measured
     *         against the last separator, so that a dot in a directory name cannot cut it short.
     */
    private static String withoutExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot <= path.lastIndexOf('/') ? path : path.substring(0, lastDot);
    }

    /** @return the file name at the end of what the document refers to, URL escapes decoded */
    private static String lastSegmentOf(String source) {
        return SourcePath.decode(segmentOf(source));
    }

    /** @return the last segment of a path, as it is - decoded again, a literal escape would go */
    private static String segmentOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * A diagram this page has already attached, and the shape of the picture it travels in.
     *
     * <p>Both are needed again when the same diagram turns up a second time on the page: the
     * source to tell the same diagram from a different one of the same name, and the shape to
     * size the second macro - which the document may ask for at another width than the first.</p>
     *
     * @param size the preview's size, or {@code null} where it could not be read
     */
    private record Diagram(String source, EmbeddedDiagram.Size size) {

        /**
         * @return how tall it is drawn that wide, or empty where either number is unknown. A
         *         width of "100%" is a width the macro has no parameter for, and a guess would
         *         squash the picture rather than place it.
         */
        String heightAt(String width) {
            try {
                return size == null
                        ? ""
                        : String.valueOf(size.heightAt(Integer.parseInt(width.trim())));
            } catch (NumberFormatException noNumber) {
                return "";
            }
        }
    }
}
