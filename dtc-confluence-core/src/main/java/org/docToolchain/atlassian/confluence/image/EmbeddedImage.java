package org.docToolchain.atlassian.confluence.image;

/**
 * The parts of a {@code data:} image URI that matter for turning it back into a file.
 *
 * @param fileExtension the extension to store the image under
 * @param encoding      how the content is encoded, in practice always base64
 * @param content       the encoded payload
 */
public record EmbeddedImage(String fileExtension, String encoding, String content) {

    /**
     * Splits a {@code data:image/png;base64,...} URI into its parts.
     */
    public static EmbeddedImage parse(String src) {
        String[] parts = src.split("[;:,]");
        String fileExtension = parts[1].split("/")[1];
        // svg+xml would not make a usable file extension
        if ("svg+xml".equals(fileExtension)) {
            fileExtension = "svg";
        }
        return new EmbeddedImage(fileExtension, parts[2], parts[3]);
    }
}
