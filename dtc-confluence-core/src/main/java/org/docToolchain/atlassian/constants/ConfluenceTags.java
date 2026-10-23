package org.docToolchain.atlassian.constants;

/**
 * Markers used while building Confluence storage format.
 *
 * <p>Confluence requires some content to sit inside CDATA sections, which Jsoup would escape if
 * they were present while the DOM is still being manipulated. The transformers therefore insert
 * these placeholder elements and {@code HtmlTransformer} swaps them for real CDATA markers once
 * the DOM has been serialised to a string.</p>
 *
 * <p>The inline comment roles are the other kind of marker here: they are one vocabulary agreed
 * between the two directions, written by the export and read by the publisher, so that the anchor
 * of an inline comment survives a round trip.</p>
 */
public final class ConfluenceTags {

    public static final String CDATA_PLACEHOLDER_START = "<cdata-placeholder>";
    public static final String CDATA_PLACEHOLDER_END = "</cdata-placeholder>";

    /**
     * What marks the text an inline comment is anchored to.
     *
     * <p>Confluence's own class name for it: a resolved comment's marker degrades to
     * {@code <span class="inline-comment-marker">} in the stored body, with no ref left, so the
     * export writes the same role for the markers it can still identify.</p>
     */
    public static final String INLINE_COMMENT_MARKER_ROLE = "inline-comment-marker";

    /**
     * What an inline comment's {@code ac:ref} travels as, in front of the ref itself.
     *
     * <p>A second role beside {@link #INLINE_COMMENT_MARKER_ROLE}, because the ref is the only
     * part of a marker that matters and a role is what both converters carry: pandoc writes a
     * span's classes as {@code [.inline-comment-marker .comment-ref-<ref>]#the marked words#} and
     * Asciidoctor renders that back to the same span.</p>
     */
    public static final String COMMENT_REF_ROLE_PREFIX = "comment-ref-";

    /**
     * What a ref may look like for either side to carry it.
     *
     * <p>Confluence writes a UUID. This is wider than a UUID and still narrow enough that a ref
     * cannot be anything but a role: a class name holding a space would be two roles, and one
     * holding punctuation would be markup in the AsciiDoc. A ref outside it is refused rather
     * than written out, on both sides.</p>
     */
    public static final String COMMENT_REF = "[A-Za-z0-9][A-Za-z0-9-]*";

    private ConfluenceTags() {
    }
}
