package org.docToolchain.atlassian.transformer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;

/**
 * Rewrites the links AsciiDoctor produced into their Confluence equivalents: document-internal
 * references become {@code ac:link} elements, and links into Jira become Jira macros.
 */
class LinkTransformer {

    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443);

    void transformLinks(Element body,
                        Map<String, String> anchors,
                        Map<String, String> pageAnchors,
                        String confluencePagePrefix,
                        String confluencePageSuffix,
                        String jiraRestApiUrl,
                        String jiraServerId) {
        String jiraBaseUrl = jiraBaseUrlFrom(jiraRestApiUrl);
        for (Element link : body.select("a[href]")) {
            String href = link.attr("href");
            if (href.startsWith("#")) {
                rewriteInternalLink(link, anchors, pageAnchors, confluencePagePrefix, confluencePageSuffix);
            } else if (jiraBaseUrl != null && href.startsWith(jiraBaseUrl + "/browse/")) {
                rewriteJiraLink(link, jiraServerId);
            }
        }
    }

    /**
     * Reduces the configured REST endpoint to the bare origin, so that browse links can be
     * recognised by prefix. An explicitly stated default port is dropped, because the links in the
     * document will not carry it.
     */
    private static String jiraBaseUrlFrom(String jiraRestApiUrl) {
        if (jiraRestApiUrl == null || jiraRestApiUrl.isEmpty()) {
            System.out.println(">>> WARN: No Jira API URL found in config, "
                    + "the Jira extension may not work as expected.");
            return null;
        }
        try {
            URI uri = new URI(jiraRestApiUrl);
            int port = uri.getPort();
            boolean portIsDefault = port == -1
                    || Integer.valueOf(port).equals(DEFAULT_PORTS.get(uri.getScheme()));
            return uri.getScheme() + "://" + uri.getHost() + (portIsDefault ? "" : ":" + port);
        } catch (URISyntaxException e) {
            System.out.println(">>> WARN: Jira API URL '" + jiraRestApiUrl + "' is not a valid URI, "
                    + "the Jira extension may not work as expected.");
            return null;
        }
    }

    private static void rewriteInternalLink(Element a,
                                            Map<String, String> anchors,
                                            Map<String, String> pageAnchors,
                                            String confluencePagePrefix,
                                            String confluencePageSuffix) {
        String anchor = a.attr("href").substring(1);
        String pageTitle = anchors.get(anchor) != null ? anchors.get(anchor) : pageAnchors.get(anchor);
        if (pageTitle == null || a.text().isEmpty()) {
            return;
        }
        // Confluence insists on link texts living inside CDATA, so the markup inside the link is
        // flattened to plain text. Styling within a link text is lost; there is no way around it.
        a.html(a.text());
        a.wrap("<ac:link" + (anchors.containsKey(anchor) ? " ac:anchor=\"" + anchor + "\"" : "") + "></ac:link>")
                .before("<ri:page ri:content-title=\""
                        + confluencePagePrefix + pageTitle + confluencePageSuffix + "\"/>")
                // Jsoup wraps into the deepest element of the fragment, so the link text ends up
                // inside the placeholder, which HtmlTransformer later turns into a CDATA section.
                .wrap("<ac:plain-text-link-body>"
                        + ConfluenceTags.CDATA_PLACEHOLDER_START + ConfluenceTags.CDATA_PLACEHOLDER_END
                        + "</ac:plain-text-link-body>")
                .unwrap();
    }

    private static void rewriteJiraLink(Element a, String jiraServerId) {
        String ticketId = a.text();
        StringBuilder macroBlock = new StringBuilder()
                .append("<ac:structured-macro ac:name=\"jira\" ac:schema-version=\"1\">\n")
                .append("                     <ac:parameter ac:name=\"key\">").append(ticketId).append("</ac:parameter>");
        if (jiraServerId != null && !jiraServerId.isEmpty()) {
            macroBlock.append("<ac:parameter ac:name=\"serverId\">").append(jiraServerId).append("</ac:parameter>");
        }
        macroBlock.append("</ac:structured-macro>");
        a.before(macroBlock.toString());
        a.remove();
    }
}
