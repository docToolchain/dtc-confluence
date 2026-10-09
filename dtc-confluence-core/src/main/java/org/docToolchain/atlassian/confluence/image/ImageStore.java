package org.docToolchain.atlassian.confluence.image;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.docToolchain.util.ContentHash;

/**
 * Finds the file behind an image reference, writing an embedded image out first if it has to.
 *
 * <p>AsciiDoctor either references an image file or inlines it as a {@code data:} URI. Confluence
 * needs a file either way, because images are uploaded as attachments.</p>
 */
public class ImageStore {

    private static final String DEFAULT_IMAGE_DIR = "images/";
    private static final String EMBEDDED_IMAGES_DIR = "/confluence/images/";

    private final List<String> imageDirs;

    /** Whether a missing file is created, or only named. See {@link #readOnly}. */
    private final boolean writing;

    public ImageStore(List<String> imageDirs) {
        this(imageDirs, true);
    }

    private ImageStore(List<String> imageDirs, boolean writing) {
        this.imageDirs = imageDirs == null ? List.of() : imageDirs;
        this.writing = writing;
    }

    /**
     * A store that answers where an embedded image would go, and puts nothing there.
     *
     * <p>A dry run has to build the body, because the hash it compares is the hash of the body a
     * real run would send - and building the body names every embedded image. Naming one is pure
     * computation: the name is the hash of the content. Creating the file is not, and a run that
     * announced it would write nothing has no business leaving a directory of PNGs behind.</p>
     */
    public static ImageStore readOnly(List<String> imageDirs) {
        return new ImageStore(imageDirs, false);
    }

    /**
     * @return where the image lives and under which name it should be attached
     */
    public StoredImage store(String basePath, String fileName, String fileExtension, String encodedContent) {
        Path existing = Path.of(basePath, imageDirContaining(basePath, fileName), fileName);
        if (existing.toFile().exists()) {
            // Composed with Path.of rather than by concatenation, the same way the lookup above
            // finds it. Concatenating produced .../assetsdiagram.png for a directory written
            // without a trailing slash, and .../images/.diagram.png for docToolchain's own
            // default of 'images/.', neither of which exists.
            return new StoredImage(existing.toString(), fileName);
        }

        System.out.println("Could not find embedded image at a known location");
        // The hash names the file, so the same image inlined twice is written once - and so the
        // name can be worked out without writing anything, which is what a dry run needs.
        String imageHash = ContentHash.md5(encodedContent);
        System.out.println("Embedded Image Hash " + imageHash);

        File image = new File(basePath + EMBEDDED_IMAGES_DIR + imageHash + "." + fileExtension);
        if (!writing) {
            return new StoredImage(image.getPath(), imageHash + "." + fileExtension);
        }
        new File(basePath + EMBEDDED_IMAGES_DIR).mkdirs();
        if (!image.exists()) {
            System.out.println("Creating image at " + basePath + EMBEDDED_IMAGES_DIR);
            write(image, encodedContent);
        }
        return new StoredImage(canonical(image), imageHash + "." + fileExtension);
    }

    /**
     * @return the first configured image directory that actually holds the file, or the default
     */
    private String imageDirContaining(String basePath, String fileName) {
        for (String configured : imageDirs) {
            String candidate = configured.replace("./", "/");
            if (Path.of(basePath, candidate, fileName).toFile().exists()) {
                return candidate;
            }
        }
        return DEFAULT_IMAGE_DIR;
    }

    private static void write(File image, String encodedContent) {
        try {
            Files.write(image.toPath(), Base64.getMimeDecoder().decode(encodedContent));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the embedded image to " + image, e);
        }
    }

    private static String canonical(File image) {
        try {
            return image.getCanonicalPath();
        } catch (IOException e) {
            return image.getAbsolutePath();
        }
    }

    /**
     * @param filePath where the image can be read from
     * @param fileName the name it should be attached under
     */
    public record StoredImage(String filePath, String fileName) {
    }
}
