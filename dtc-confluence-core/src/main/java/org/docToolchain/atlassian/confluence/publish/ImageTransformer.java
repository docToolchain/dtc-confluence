package org.docToolchain.atlassian.confluence.publish;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.docToolchain.atlassian.confluence.image.EmbeddedImage;
import org.docToolchain.atlassian.confluence.image.ImageStore;
import org.jsoup.nodes.Element;

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
    public ImageTransformer(String baseUrl, List<String> imageDirs) {
        this.baseUrl = baseUrl;
        this.imageDirs = imageDirs;
    }

    /**
     * @return the files that have to be attached once the page exists
     */
    public List<Upload> transformImages(Element body) {
        List<Upload> uploads = new ArrayList<>();
        for (Element image : body.select("img")) {
            String source = image.attr("src");
            String width = valueOr(image.attr("width"), DEFAULT_WIDTH);
            String alignment = valueOr(image.attr("align"), DEFAULT_ALIGNMENT);

            if (source.startsWith("http")) {
                // Nothing to attach: it stays a reference, and shows only while that host serves it.
                image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                        + "\"><ri:url ri:value=\"" + source + "\"/></ac:image>");
            } else {
                uploads.add(attach(image, source, width, alignment));
            }
            image.remove();
        }
        return uploads;
    }

    private Upload attach(Element image, String source, String width, String alignment) {
        String directory = baseUrl.replaceAll("\\\\", "/").replaceAll("/[^/]*$", "/");
        String url;
        String fileName;
        if (source.startsWith("data:image")) {
            EmbeddedImage embedded = EmbeddedImage.parse(source);
            String extension = embedded.fileExtension();
            String named = image.attr("alt").replaceAll("\\s+", "_") + "." + extension;
            ImageStore.StoredImage stored =
                    new ImageStore(imageDirs).store(directory, named, extension, embedded.content());
            url = stored.filePath();
            fileName = stored.fileName();
        } else {
            url = directory + source;
            fileName = decode(source.substring(source.lastIndexOf('/') + 1));
        }
        url = decode(url);
        System.out.println("    image: " + url);
        image.after("<ac:image ac:align=\"" + alignment + "\" ac:width=\"" + width
                + "\"><ri:attachment ri:filename=\"" + fileName + "\"/></ac:image>");
        return new Upload(url, fileName, "automatically uploaded");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // A per-cent sign that is not an escape: the name means itself.
            return value;
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
