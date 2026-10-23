package org.docToolchain.atlassian.confluence.export;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads a Jira's base URL out of the page Confluence rendered.
 *
 * <p>A rendered {@code jira} macro carries both halves of the answer: the server it belongs to, in
 * {@code data-client-id="SINGLE_<serverId>_..."}, and the link the server built,
 * {@code href=".../browse/MNG-5958"}. The base URL is that link up to {@code /browse/}. A macro
 * driven by a query renders {@code .../issues/?jql=...} instead, and the base is the part before
 * {@code /issues/}.</p>
 *
 * <p>Not every macro renders a link. A {@code count=true} macro - 285 of the 296 query macros
 * measured in the Maven space - renders a placeholder that a browser resolves later, so a page
 * holding nothing else answers nothing. That is why only an answer is remembered, never the lack
 * of one: a failure belongs to the page, not to the server, and remembering it per server would
 * let one such page cost the whole export every link it has. What is remembered per page is that
 * it has been looked at, so a page of 104 macros still costs one request.</p>
 *
 * <p>Not thread-safe, and does not need to be: an export walks its pages one at a time.</p>
 */
public class RenderedJiraServers implements JiraServers {

    /** What a rendered macro is tagged with: its kind, then the server it belongs to. */
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z]+_([^_]+)_.*");

    private static final String BROWSE = "/browse/";

    private static final String FILTER = "/issues/?jql=";

    private final ConfluenceReader reader;

    /** Base URL per server id, for the servers that have answered. */
    private final Map<String, String> resolved = new HashMap<>();

    /** The pages already fetched, so that no page is fetched twice. */
    private final Set<String> asked = new HashSet<>();

    public RenderedJiraServers(ConfluenceReader reader) {
        this.reader = reader;
    }

    @Override
    public String baseUrlFor(String serverId, String pageId) {
        if (serverId == null || serverId.isBlank() || pageId == null) {
            return null;
        }
        String known = resolved.get(serverId);
        if (known != null) {
            return known;
        }
        if (!asked.add(pageId)) {
            // This page has already been read and did not name this server's URL. Another page
            // still might, so nothing is written to resolved.
            return null;
        }
        // Everything the page names, from the one request: a page can carry two servers, and
        // reading only the one that was asked for would leave the other unresolved although its
        // answer was in the same body - and the page is never fetched again.
        readFrom(reader.fetchRenderedBody(pageId)).forEach(resolved::putIfAbsent);
        return resolved.get(serverId);
    }

    /**
     * Every Jira the rendered page names, by the server id its macros carry.
     *
     * <p>Read from inside the element a macro rendered, and from nowhere else. A page's own text
     * holds Jira links too - the Maven space writes them by hand in table cells - and a macro that
     * rendered no link of its own would otherwise be given one of those, which on a page linking
     * two Jiras is a link that resolves to a real but wrong issue.</p>
     */
    private static Map<String, String> readFrom(String renderedBody) {
        if (renderedBody == null) {
            return Map.of();
        }
        Map<String, String> found = new HashMap<>();
        Document dom = Jsoup.parse(renderedBody);
        for (Element macro : dom.select("[data-client-id]")) {
            Matcher clientId = CLIENT_ID.matcher(macro.attr("data-client-id"));
            if (!clientId.matches() || found.containsKey(clientId.group(1))) {
                continue;
            }
            for (Element link : macro.select("a[href]")) {
                String base = baseUrlOf(link.attr("href"));
                if (base != null) {
                    found.put(clientId.group(1), base);
                    break;
                }
            }
        }
        return found;
    }

    /** @return what stands in front of the issue or the filter in {@code href}, if it is a Jira link */
    private static String baseUrlOf(String href) {
        if (!href.startsWith("http://") && !href.startsWith("https://")) {
            return null;
        }
        for (String marker : new String[] {BROWSE, FILTER}) {
            int at = href.indexOf(marker);
            if (at > 0) {
                return href.substring(0, at);
            }
        }
        return null;
    }
}
