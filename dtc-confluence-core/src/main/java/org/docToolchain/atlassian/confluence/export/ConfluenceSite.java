package org.docToolchain.atlassian.confluence.export;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The Confluence an export was read from, and how a URL into it is spelled.
 *
 * <p>A link whose target is not part of the export has nothing local to point at: the page it
 * names was never written. As a URL into the Confluence it came from the link still reaches what
 * it was about, which is the difference between "see here" and the bare words {@code see here}.
 * 82 of the 173 {@code ac:link} elements in the 136 pages of the Maven space name a page that
 * way, and most of those pages are outside any one export.</p>
 *
 * <p>The shape is Confluence's own: {@code /display/<SPACE>/<Title+with+pluses>}. Measured
 * against Confluence DC 9.2.21, {@code /display/MAVEN/Baseline+Upgrade+to+Maven+3.5.4} answers
 * with a redirect to {@code /spaces/MAVEN/pages/199539909/Baseline+Upgrade+to+Maven+3.5.4} - and
 * that canonical form cannot be built here, because the storage format carries the title and the
 * id is something only the server can resolve. A page of the space's own authors writes the same
 * shape by hand.</p>
 *
 * @param baseUrl  where Confluence is served, context path included and without a trailing slash
 * @param spaceKey the space the export was read from, which an {@code ac:link} names only where
 *                 its target is in a different one
 */
record ConfluenceSite(String baseUrl, String spaceKey) {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    ConfluenceSite(String baseUrl, String spaceKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.spaceKey = spaceKey == null ? "" : spaceKey;
    }

    /** @return whether a URL into this Confluence can be built at all */
    boolean isKnown() {
        return !baseUrl.isEmpty() && !spaceKey.isEmpty();
    }

    /**
     * @param space  the space of the target, empty where the link names none and the export's own
     *               space is meant
     * @param title  the target's title, which is what an {@code ac:link} carries
     * @param anchor the anchor on that page, empty where the link names none
     * @return the page as a URL, or empty where this Confluence is not known
     */
    String pageUrl(String space, String title, String anchor) {
        if (!isKnown()) {
            return "";
        }
        String url = baseUrl + "/display/" + encode(space.isEmpty() ? spaceKey : space)
                + "/" + encode(title);
        return anchor.isEmpty() ? url : url + "#" + anchorId(title, anchor);
    }

    /**
     * The id Confluence gives an anchor: the title of the page it stands on and the anchor's own
     * name, each with its spaces taken out, joined by a hyphen.
     *
     * <p>Read off the server's own rendering rather than guessed: on the Maven space's
     * <em>Git Migration</em> page the link to the anchor
     * {@code Migratinganaggregatortreeintoacollectionofgitrepos} is rendered as
     * {@code href="#GitMigration-Migratinganaggregatortreeintoacollectionofgitrepos"}, and the
     * heading it belongs to carries that same id.</p>
     */
    private static String anchorId(String title, String anchor) {
        return withoutSpaces(title) + "-" + withoutSpaces(anchor);
    }

    /**
     * Percent-encoded as a path segment, keeping Confluence's own spelling of a space: a plus,
     * which is what {@link URLEncoder} writes and what the URLs Confluence hands out carry. The
     * query-string reading of a plus does not apply under {@code /display}, where the server
     * reads it back as the space it stood for.
     */
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private static String withoutSpaces(String text) {
        return WHITESPACE.matcher(text).replaceAll("");
    }
}
