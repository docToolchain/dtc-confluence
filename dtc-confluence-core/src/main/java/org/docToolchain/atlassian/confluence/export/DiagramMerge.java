package org.docToolchain.atlassian.confluence.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.docToolchain.atlassian.confluence.image.EmbeddedDiagram;
import org.docToolchain.util.ContentHash;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Makes one file in the repository out of the two or three attachments a draw.io diagram is.
 *
 * <p>On a Confluence page a diagram is an {@code mxfile} attached under a name with no extension,
 * a preview {@code <name>.png} beside it, and for every view pinned with an {@code aspect} a
 * further {@code <name>-<sha1 of the aspect>.png}. Downloaded as they are, the picture is referred
 * to by the document and the diagram by nothing at all - so a publish of that document sends a
 * picture back and the diagram is lost. Merged, the picture carries its own source: the document
 * still refers to one {@code image::}, and the next publish finds the diagram inside it and writes
 * the macro again.</p>
 *
 * <p>The merged file keeps the preview's name, not the {@code .drawio.png} the Groovy original
 * wrote. The name is what the publish side derives the diagram's name from, and {@code x.drawio}
 * is not {@code x}: renamed, the republished page would attach a second diagram beside the one it
 * came from and leave the first orphaned.</p>
 *
 * <h2>The diagram is the master</h2>
 *
 * <p>The source written into the picture is read from the {@code mxfile}, never out of the
 * preview. A preview is derived, and a derived file may be older than what it derives from -
 * measured on a real page, where a human edited two pinned views and the app refreshed the
 * default preview and neither pinned one.</p>
 *
 * <p>The merged file's name carries both revisions, {@code <picture>-<diagram>_<name>.png}: the
 * revision is the only thing that tells the publish side whether somebody has written an
 * attachment since the export, and this one file goes back as two attachments that Confluence
 * writes apart. One number for both would make the publish right after an export refuse the
 * preview nobody had touched, and let a preview somebody else had written pass as ours.</p>
 *
 * <h2>Staleness</h2>
 *
 * <p>Detected by a comparison that costs nothing - the attachment listing already carries when
 * each revision was written, so a preview older than its diagram is stale. It is always reported,
 * naming the view, and drawn again only where a renderer happens to be on the machine. Either way
 * the exported file carries the current diagram, so what is old is the picture and not the
 * source.</p>
 */
public class DiagramMerge {

    /** What the draw.io app gives the diagram it attaches. Our own publisher sends octet-stream. */
    private static final String MXFILE_MEDIA_TYPE = "application/vnd.jgraph.mxfile";

    /** What a file Confluence was given no type for is served as, ours among them. */
    private static final String UNTYPED_MEDIA_TYPE = "application/octet-stream";

    /** The forty hex digits the app puts between a diagram's name and {@code .png} for a view. */
    private static final Pattern PINNED = Pattern.compile("-[0-9a-f]{40}\\.png$");

    private final Path imageRoot;

    /** Where a picture can be drawn again, or {@code null} - which is the normal case. */
    private final DiagramRenderer renderer;

    public DiagramMerge(Path imageRoot) {
        this(imageRoot, null);
    }

    public DiagramMerge(Path imageRoot, DiagramRenderer renderer) {
        this.imageRoot = imageRoot;
        this.renderer = renderer;
    }

    /**
     * Merges every diagram of this tree, in the files the downloader has written.
     *
     * <p>The tree is changed along with the files, because the two have to agree: the preview's
     * entry takes the diagram's revision, which is the name the merged file was written under, and
     * the diagram's own entry is removed so that nothing downstream offers a file that is now part
     * of a picture.</p>
     *
     * @return what was merged, and what has to be said about it
     */
    public Merged merge(ExportedTree tree) {
        List<String> diagrams = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // Over a copy: a merge removes the diagram's own entry from the map it is reading.
        for (Map.Entry<String, Map<String, Object>> entry
                : new ArrayList<>(tree.getAttachments().entrySet())) {
            mergeOne(tree, entry.getKey(), entry.getValue(), diagrams, problems);
        }
        return new Merged(diagrams, problems);
    }

    private void mergeOne(ExportedTree tree, String id, Map<String, Object> diagram,
                          List<String> diagrams, List<String> problems) {
        if (!couldBeADiagram(diagram)) {
            return;
        }
        String source = readText(fileOf(tree, diagram));
        if (source == null || !EmbeddedDiagram.isDiagram(source)) {
            return;
        }
        String name = text(diagram.get("filename"));
        String pageId = text(diagram.get("pageId"));
        Map<String, String> views = pinnedViews(tree.getBodies().get(pageId));

        for (Map<String, Object> pinned : previewsOf(tree, pageId, name, true)) {
            // The view's own id, which is what draws it. The file name carries the hash of it and
            // a hash cannot be undone, so a view this page no longer pins cannot be drawn at all -
            // and drawing the first page instead would put the wrong view in its place.
            String aspect = views.get(hashIn(pinned, name));
            refresh(pinned, diagram, source, viewOf(pinned, name, views), aspect, tree, problems);
        }

        List<Map<String, Object>> previews = previewsOf(tree, pageId, name, false);
        if (previews.isEmpty()) {
            problems.add("the diagram '" + name + "' has no " + name + ".png attached, so there is"
                    + " no picture for it to travel inside; it is exported as the bare diagram,"
                    + " which nothing in the document refers to");
            return;
        }
        Map<String, Object> preview = previews.get(0);
        // The default preview shows the file's first page, which is what an empty aspect draws.
        byte[] picture = refresh(preview, diagram, source, "'" + name + "'", "", tree, problems);
        if (picture == null) {
            problems.add("the preview " + name + ".png of the diagram '" + name + "' was not"
                    + " written to disk, so there is nothing to merge the diagram into");
            return;
        }

        // Both revisions, because this one file goes back as two attachments: the picture's
        // first, as every other file's name carries its own, and the diagram's behind it.
        String revisions = text(preview.get("version")) + "-" + text(diagram.get("version"));
        Path target = fileOf(tree, diagram)
                .resolveSibling(revisions + "_" + fileName(name) + ".png");
        try {
            Files.write(target, EmbeddedDiagram.embedIn(picture, source));
        } catch (IOException | IllegalArgumentException notMerged) {
            problems.add("the diagram '" + name + "' could not be put into its preview: "
                    + notMerged.getMessage());
            return;
        }
        remove(fileOf(tree, preview), target);
        remove(fileOf(tree, diagram), target);
        // The name the merged file was written under, so that the document refers to that one.
        preview.put("version", revisions);
        tree.getAttachments().remove(id);
        diagrams.add(name);
    }

    /**
     * Says that a preview is older than its diagram, and draws it again where that is possible.
     *
     * @param aspect the view to draw, empty for the file's first page, or {@code null} where it
     *               cannot be told - then nothing is drawn, because the wrong view is worse than
     *               an old picture of the right one
     * @return the picture to use, refreshed where it could be, or {@code null} where the file is
     *         not on disk at all
     */
    private byte[] refresh(Map<String, Object> preview, Map<String, Object> diagram, String source,
                           String view, String aspect, ExportedTree tree, List<String> problems) {
        Path file = fileOf(tree, preview);
        byte[] picture = readBytes(file);
        if (!isOlder(preview, diagram)) {
            return picture;
        }
        Optional<byte[]> drawn = renderer == null || aspect == null
                ? Optional.empty()
                : renderer.render(source, aspect);
        String outcome = drawn.isPresent()
                ? "drawn again from the diagram by " + renderer
                : "exported as it is, with the current diagram inside it";
        problems.add("the preview of " + view + " is older than its diagram ("
                + text(preview.get("written")) + " against " + text(diagram.get("written"))
                + "), so it shows an earlier state wherever no browser runs the viewer - an export"
                + " to PDF, for one. It is " + outcome);
        if (drawn.isEmpty()) {
            return picture;
        }
        write(file, drawn.get(), problems);
        return drawn.get();
    }

    /**
     * @return whether this is worth reading off the disk to see whether it is a diagram. The media
     *         type is the app's own, so it answers for anything the app attached; a diagram this
     *         project published went up as {@code application/octet-stream}, which is the second
     *         answer, and a name with no extension at all the third.
     *
     *     <p>Why the untyped case is not the name's to cover: the diagram of
     *     {@code architecture.context.png} is attached as {@code architecture.context}, a name
     *     with a dot in it. Candidates are cheap - what decides is {@code <mxfile}, read off the
     *     file itself - and a source skipped here is a source silently left behind.</p>
     */
    private static boolean couldBeADiagram(Map<String, Object> attachment) {
        String mediaType = text(attachment.get("mediaType"));
        return MXFILE_MEDIA_TYPE.equalsIgnoreCase(mediaType)
                || UNTYPED_MEDIA_TYPE.equalsIgnoreCase(mediaType)
                || !text(attachment.get("filename")).contains(".");
    }

    /**
     * @param pinned whether to answer the previews of the pinned views rather than the one of the
     *               diagram itself
     */
    private static List<Map<String, Object>> previewsOf(ExportedTree tree, String pageId,
                                                        String name, boolean pinned) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> attachment : tree.getAttachments().values()) {
            if (!pageId.equals(text(attachment.get("pageId")))) {
                continue;
            }
            String candidate = text(attachment.get("filename"));
            boolean isPinned = candidate.startsWith(name + "-")
                    && PINNED.matcher(candidate.substring(name.length())).find();
            if (pinned ? isPinned : candidate.equals(name + ".png")) {
                found.add(attachment);
            }
        }
        return found;
    }

    /**
     * The views pinned on this page, by the hash the app names their previews after.
     *
     * <p>Read off the storage format, because the preview's name carries the hash and not the
     * view, and a message that named a hash would name nothing a reader of the document knows.</p>
     */
    private static Map<String, String> pinnedViews(String body) {
        Map<String, String> views = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return views;
        }
        for (Element macro : Jsoup.parse(body, "", Parser.xmlParser())
                .select("ac|structured-macro[ac:name=drawio]")) {
            for (Element parameter : macro.children()) {
                if ("ac:parameter".equals(parameter.tagName())
                        && "aspect".equals(parameter.attr("ac:name"))
                        && !parameter.text().isEmpty()) {
                    views.put(ContentHash.sha1(parameter.text()), parameter.text());
                }
            }
        }
        return views;
    }

    /** @return the view this preview belongs to, named as the page names it where that is known */
    private static String viewOf(Map<String, Object> preview, String name,
                                 Map<String, String> views) {
        String hash = hashIn(preview, name);
        String view = views.get(hash);
        return view == null
                ? "the pinned view of '" + name + "' behind " + hash
                : "the view '" + view + "' of '" + name + "'";
    }

    /** @return the hash in a pinned preview's name, which is the sha1 of the view's id */
    private static String hashIn(Map<String, Object> preview, String name) {
        String candidate = text(preview.get("filename"));
        String tail = candidate.length() > name.length() ? candidate.substring(name.length()) : "";
        return PINNED.matcher(tail).find()
                ? tail.substring(1, tail.length() - ".png".length())
                : "";
    }

    /**
     * @return where the downloader wrote this attachment, by the same rule it used
     */
    private Path fileOf(ExportedTree tree, Map<String, Object> attachment) {
        Path directory = imageRoot;
        for (String folder
                : PageNaming.attachmentFolders(tree.getPages(), text(attachment.get("pageId")))) {
            directory = directory.resolve(folder);
        }
        return directory.resolve(text(attachment.get("version")) + "_"
                + fileName(text(attachment.get("filename"))));
    }

    /**
     * @return whether the preview was written before the diagram it shows. Both spellings come
     *         from one listing of one server, so comparing them as text compares the instants;
     *         where either is missing nothing is claimed.
     */
    private static boolean isOlder(Map<String, Object> preview, Map<String, Object> diagram) {
        String shown = text(preview.get("written"));
        String written = text(diagram.get("written"));
        return !shown.isEmpty() && !written.isEmpty() && shown.compareTo(written) < 0;
    }

    private static String fileName(String filename) {
        return PageNaming.sanitizeAttachmentName(filename);
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException notThere) {
            return null;
        }
    }

    private static String readText(Path file) {
        byte[] content = readBytes(file);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    private static void write(Path file, byte[] content, List<String> problems) {
        try {
            Files.write(file, content);
        } catch (IOException notWritten) {
            problems.add("could not write " + file + ": " + notWritten.getMessage());
        }
    }

    /** Removes a file the merge has consumed, unless that file is the merge's own answer. */
    private static void remove(Path file, Path target) {
        if (file.equals(target)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException leftBehind) {
            System.out.println(">>> WARN: " + file + " is part of a picture now but could not be"
                    + " removed: " + leftBehind.getMessage());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * @param diagrams the diagrams that are one file each now, by the name Confluence knows them
     * @param problems what has to be said, one line each, in the order it was found
     */
    public record Merged(List<String> diagrams, List<String> problems) {
    }
}
