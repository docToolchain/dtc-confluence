package org.docToolchain.atlassian.confluence.publish;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The file name behind the {@code src} or {@code href} of a generated document.
 *
 * <p>Asciidoctor per-cent encodes what a URL cannot carry literally - a space becomes
 * {@code %20} - so the name has to be decoded before a file of that name can be read or
 * uploaded.</p>
 */
final class SourcePath {

    private SourcePath() {
    }

    /**
     * Decodes the per-cent escapes of a path, and only those.
     *
     * <p>{@link URLDecoder} decodes a form field, where {@code +} stands for a space. A path is
     * not a form field: in {@code images/C++ guide.png} the plus means itself. Decoded as a form
     * field, the image was uploaded as {@code C   guide.png} - a name no file on disk has, so
     * the page ended up pointing at an attachment that was never uploaded. The pluses are
     * therefore escaped before decoding and come back out as themselves.</p>
     *
     * @return the path with its escapes resolved, or unchanged where it carries none
     */
    static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // A per-cent sign that is not an escape: the name means itself.
            return value;
        }
    }
}
