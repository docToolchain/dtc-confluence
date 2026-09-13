package org.docToolchain.util;

import java.math.BigInteger;
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

    private ContentHash() {
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
