package org.docToolchain.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashes content so that an unchanged page or attachment can be recognised and left alone.
 *
 * <p>The hash is not a security measure - it only has to differ when the content differs.</p>
 */
public final class ContentHash {

    private static final int HEX_RADIX = 16;
    private static final int MD5_HEX_LENGTH = 32;
    private static final int SHA1_HEX_LENGTH = 40;

    private ContentHash() {
    }

    /**
     * @return the SHA-1 of the text, as lower-case hex
     *
     *     <p>Not a choice of ours: the draw.io app names the preview of a pinned view
     *     {@code <diagram>-<sha1 of the aspect>.png}, so this is the only way to read which view
     *     such a preview belongs to. Measured on a real page -
     *     {@code sha1("view-building-blocks")} is the hex in
     *     {@code arch-multipage-3f7fef23c469fa17e86e55c813d4ce1da8b1945d.png}.</p>
     */
    @SuppressWarnings("java:S4790") // not used for security: it reproduces another app's naming
    public static String sha1(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            String hex = new BigInteger(1, digest).toString(HEX_RADIX);
            return "0".repeat(SHA1_HEX_LENGTH - hex.length()) + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required to be present in every JVM", e);
        }
    }

    /**
     * @return the MD5 of the text, as lower-case hex
     */
    @SuppressWarnings("java:S4790") // not used for security, only for change detection
    public static String md5(String text) {
        try {
            // getBytes() without a charset, deliberately: the Groovy this replaces used the
            // platform default, and changing it would change every stored hash at once and
            // re-upload every attachment. See docs/decisions.adoc.
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes());
            String hex = new BigInteger(1, digest).toString(HEX_RADIX);
            return "0".repeat(MD5_HEX_LENGTH - hex.length()) + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required to be present in every JVM", e);
        }
    }
}
