package org.docToolchain.atlassian.transformer;

import java.util.regex.Pattern;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;

/**
 * Writes the anchor of an inline comment back into the page body.
 *
 * <p>A publish replaces the whole body. The comment object survives that - it lives beside the
 * page - but its anchor is the {@code ac:inline-comment-marker} element around the words it was
 * made on, so a body generated without that element leaves every comment on the page dangling and
 * the highlight gone. Measured over four versions of one cwiki page: the element alone decides,
 * and Confluence accepts one written by us with a ref it did not issue.</p>
 *
 * <p>The export carries the ref out as a role on the marked span, so this is the other half of
 * that round trip and reads the same vocabulary. A span marked as a comment anchor but carrying no
 * ref is left as it is: that is what Confluence itself writes for a resolved comment, and a marker
 * without a ref anchors nothing.</p>
 */
public class InlineCommentTransformer {

    private static final Pattern COMMENT_REF = Pattern.compile(ConfluenceTags.COMMENT_REF);

    public void transformInlineComments(Element body) {
        for (Element span : body.select("span." + ConfluenceTags.INLINE_COMMENT_MARKER_ROLE)) {
            String ref = refOf(span);
            if (ref == null) {
                continue;
            }
            span.tagName("ac:inline-comment-marker");
            // Every role goes with the tag: the class attribute said what the element now is, and
            // Confluence reads nothing else off a marker. Set through the attribute API, so that a
            // ref means itself rather than being parsed again as markup.
            span.clearAttributes();
            span.attr("ac:ref", ref);
        }
    }

    /**
     * @return the ref the roles of this span carry, or null where they carry none this can use
     */
    private static String refOf(Element span) {
        for (String role : span.classNames()) {
            if (!role.startsWith(ConfluenceTags.COMMENT_REF_ROLE_PREFIX)) {
                continue;
            }
            String ref = role.substring(ConfluenceTags.COMMENT_REF_ROLE_PREFIX.length());
            if (COMMENT_REF.matcher(ref).matches()) {
                return ref;
            }
        }
        return null;
    }
}
