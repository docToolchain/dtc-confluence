package org.docToolchain.atlassian.confluence.publish;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public class ImageTransformer {

    /** What Confluence uses when the document says nothing. */
    private static final String DEFAULT_WIDTH = "500";

    private static final String DEFAULT_ALIGNMENT = "center";

    private final String baseUrl;
    private final List<String> imageDirs;

    /**
     * @param baseUrl   the HTML file the images are relative to
     * @param imageDirs where to look for an image, and where to write an embedded one
     */
    /** Whether an embedded image that is not on disk yet is written there. */
    private final boolean writing;

    public ImageTransformer(String baseUrl, List<String> imageDirs) {
        this(baseUrl, imageDirs, true);
    }

    /**
     * @param writing false for a dry run, which needs the names of the embedded images to build
     *                the body it compares and must not leave the files behind
     */
    public ImageTransformer(String baseUrl, List<String> imageDirs, boolean writing) {
        this.baseUrl = baseUrl;
        this.imageDirs = imageDirs;
        this.writing = writing;
    }

    /**
     * @return the files that have to be attached once the page exists
     */
    public List<Upload> transformImages(Element body) {
        List<Upload> uploads = new ArrayList<>();
        Elements images = body.select("img");
        Set<String> merging = namesTwoFilesWouldShare(images);
        for (Element image : images) {
            String source = image.attr("src");
            String width = valueOr(image.attr("width"), DEFAULT_WIDTH);
            String alignment = valueOr(image.attr("align"), DEFAULT_ALIGNMENT);

            if (source.startsWith("http")) {
                // Nothing to attach: it stays a reference, and shows only while that host serves it.
                image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                        + "\"><ri:url ri:value=\"" + source + "\"/></ac:image>");
            } else {
                uploads.add(attach(image, source, width, alignment, merging));
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
     * the page is known.</p>
     *
     * @return the shortened names that more than one file lays claim to
     */
    private static Set<String> namesTwoFilesWouldShare(Elements images) {
        Map<String, Set<String>> filesByName = new LinkedHashMap<>();
        for (Element image : images) {
            String source = image.attr("src");
            if (source.startsWith("http") || source.startsWith("data:image")) {
                continue;
            }
            filesByName
                    .computeIfAbsent(AttachmentName.of(lastSegmentOf(source)).fileName(),
                            name -> new LinkedHashSet<>())
                    .add(SourcePath.decode(source));
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

    private Upload attach(Element image, String source, String width, String alignment,
                          Set<String> merging) {
        String directory = baseUrl.replaceAll("\\\\", "/").replaceAll("/[^/]*$", "/");
        String url;
        String fileName;
        int exportedVersion = AttachmentName.NO_VERSION;
        if (source.startsWith("data:image")) {
            EmbeddedImage embedded = EmbeddedImage.parse(source);
            String extension = embedded.fileExtension();
            String named = image.attr("alt").replaceAll("\\s+", "_") + "." + extension;
            ImageStore store = writing ? new ImageStore(imageDirs) : ImageStore.readOnly(imageDirs);
            ImageStore.StoredImage stored =
                    store.store(directory, named, extension, embedded.content());
            url = stored.filePath();
            fileName = stored.fileName();
        } else {
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
            }
        }
        url = SourcePath.decode(url);
        System.out.println("    image: " + url);
        // Escaped, because the fragment is parsed again: a quote in the name - the data-URI
        // branch takes it from the alt text - would close the attribute and cut the name short,
        // leaving the page pointing at an attachment nobody uploads.
        image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                + "\"><ri:attachment ri:filename=\"" + Entities.escape(fileName)
                + "\"/></ac:image>");
        return new Upload(url, fileName, "automatically uploaded", exportedVersion);
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
}
