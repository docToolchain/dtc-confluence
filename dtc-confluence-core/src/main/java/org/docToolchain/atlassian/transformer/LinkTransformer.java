package org.docToolchain.atlassian.transformer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
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
                        String jiraServerId,
                        DocumentLinks documentLinks,
                        String from) {
        String jiraBaseUrl = jiraBaseUrlFrom(jiraRestApiUrl);
        for (Element link : body.select("a[href]")) {
            String href = link.attr("href");
            if (href.startsWith("#")) {
                rewriteInternalLink(link, anchors, pageAnchors, confluencePagePrefix, confluencePageSuffix);
            } else if (jiraBaseUrl != null && href.startsWith(jiraBaseUrl + "/browse/")) {
                rewriteJiraLink(link, jiraServerId);
            } else if (namesAnotherDocument(href)) {
                rewriteDocumentLink(link, href, documentLinks, from);
            }
        }
    }

    /**
     * Whether a link is one document of this project pointing at another.
     *
     * <p>Which is what an {@code xref:other.adoc[]} has become by now: AsciiDoctor rewrites the
     * extension, so it arrives here as {@code other.html}. Judged by that extension and nothing
     * else, deliberately - a relative link to a PDF or an image is a link to a file and is left to
     * the attachment handling, and a link with a scheme points out of the project entirely.</p>
     */
    private static boolean namesAnotherDocument(String href) {
        if (href.isEmpty() || href.contains("://") || href.startsWith("mailto:")) {
            return false;
        }
        int hash = href.indexOf('#');
        String file = (hash < 0 ? href : href.substring(0, hash)).toLowerCase(Locale.ROOT);
        return file.endsWith(".html") || file.endsWith(".htm");
    }

    /**
     * Points a link at the Confluence page the other document became.
     *
     * <p>Left exactly as it was where this run publishes no such document, and said so rather than
     * passed over: the link would otherwise reach Confluence as a relative link to a file that was
     * never put there, which is what it looks like today. A reader finds that out by clicking it;
     * the run can say it at once.</p>
     *
     * <p>A link into a document that <em>is</em> published, at an anchor that document does not
     * carry, is a different matter and gets different words. It lands on the page, which is not
     * the spot the link named but is the document the reader was after - and it is a link that
     * works, which a relative one to an {@code .html} file never is here.</p>
     */
    private static void rewriteDocumentLink(Element a, String href, DocumentLinks documentLinks,
                                            String from) {
        DocumentLinks.Resolution resolution = documentLinks.resolve(from, href);
        if (!resolution.namesAPublishedDocument()) {
            System.out.println(">>> WARN: " + from + " links to '" + href + "', which this run"
                    + " does not publish. The link is left as it is and will not resolve in"
                    + " Confluence. Add that document to confluence.input, or point the link"
                    + " somewhere this run writes.");
            return;
        }
        if (resolution.anchorIsMissing()) {
            System.out.println(">>> WARN: " + from + " links to '" + href + "'. That document is"
                    + " published, but carries no anchor '" + resolution.missingAnchor() + "', so"
                    + " the link points at the page itself instead of the spot it named."
                    + didYouMean(resolution.nearestAnchor()));
        }
        if (a.text().isEmpty()) {
            return;
        }
        DocumentLinks.Target target = resolution.target().orElseThrow();
        linkTo(a, target.pageTitle(), target.anchor());
    }

    /**
     * @return the near miss spelled out, where there is one. It is worth saying because the
     *         mistake behind it is invisible in the document: {@code xref:other#the-part.adoc[]}
     *         puts the extension on the anchor rather than on the file, and AsciiDoctor passes
     *         that through without a word.
     */
    private static String didYouMean(String nearestAnchor) {
        return nearestAnchor.isEmpty() ? ""
                : " Did you mean '#" + nearestAnchor + "'? The file extension belongs in front of"
                        + " the '#', as in 'xref:other.adoc#" + nearestAnchor + "[]'.";
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
        linkTo(a, confluencePagePrefix + pageTitle + confluencePageSuffix,
                anchors.containsKey(anchor) ? anchor : "");
    }

    /**
     * @param pageTitle the title as Confluence holds it, decorated already - a cross-document link
     *                  carries the prefix and suffix of the document it points at, which need not
     *                  be the ones of the document it sits in
     * @param anchor    the anchor to open at, empty for the page itself
     */
    private static void linkTo(Element a, String pageTitle, String anchor) {
        // Confluence insists on link texts living inside CDATA, so the markup inside the link is
        // flattened to plain text. Styling within a link text is lost; there is no way around it.
        a.html(a.text());
        a.wrap("<ac:link" + (anchor.isEmpty() ? "" : " ac:anchor=\"" + anchor + "\"") + "></ac:link>")
                .before("<ri:page ri:content-title=\"" + pageTitle + "\"/>")
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
