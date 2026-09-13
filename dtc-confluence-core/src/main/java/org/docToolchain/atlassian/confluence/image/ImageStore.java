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

    public ImageStore(List<String> imageDirs) {
        this.imageDirs = imageDirs == null ? List.of() : imageDirs;
    }

    /**
     * @return where the image lives and under which name it should be attached
     */
    public StoredImage store(String basePath, String fileName, String fileExtension, String encodedContent) {
        String imageDir = imageDirContaining(basePath, fileName);
        if (Path.of(basePath, imageDir, fileName).toFile().exists()) {
            return new StoredImage(basePath + imageDir + fileName, fileName);
        }

        System.out.println("Could not find embedded image at a known location");
        new File(basePath + EMBEDDED_IMAGES_DIR).mkdirs();
        // The hash names the file, so the same image inlined twice is written once.
        String imageHash = ContentHash.md5(encodedContent);
        System.out.println("Embedded Image Hash " + imageHash);

        File image = new File(basePath + EMBEDDED_IMAGES_DIR + imageHash + "." + fileExtension);
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
