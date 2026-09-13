package org.docToolchain.atlassian.transformer;

import java.util.Map;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;

/**
 * Converts an AsciiDoctor-generated HTML body into Confluence storage format.
 */
public class HtmlTransformer {

    /**
     * Confluence renders a language parameter padded with whitespace as an unknown language.
     * See docToolchain issue #402.
     */
    private static final String PADDED_LANGUAGE_PARAMETER =
            "(?m)(ac:name=\"language\">)([\\n\\r\\t ]*)([a-z]+)([\\n\\r\\t ]*)(</ac)";

    private final CodeBlockTransformer codeBlockTransformer = new CodeBlockTransformer();
    private final LinkTransformer linkTransformer = new LinkTransformer();

    private String jiraServerId;
    private String jiraBaseUrl;

    public HtmlTransformer withJiraIntegration(String jiraBaseUrl) {
        this.jiraBaseUrl = jiraBaseUrl;
        return this;
    }

    public HtmlTransformer usingOnPremiseJira(String jiraServerId) {
        this.jiraServerId = jiraServerId;
        return this;
    }

    public String transformToConfluenceFormat(Element body,
                                              Map<String, String> anchors,
                                              Map<String, String> pageAnchors,
                                              String confluencePagePrefix,
                                              String confluencePageSuffix) {
        codeBlockTransformer.transformCodeBlock(body);
        linkTransformer.transformLinks(body, anchors, pageAnchors,
                confluencePagePrefix, confluencePageSuffix, jiraBaseUrl, jiraServerId);
        return sanitizeBody(body);
    }

    private static String sanitizeBody(Element body) {
        // Normalising the markup first, while everything Jsoup serialised is still escaped: a code
        // sample containing the literal text <br> or <hr> is only unescaped afterwards and so is
        // published as it was written, instead of being rewritten into XHTML.
        String html = body.html().trim()
                .replace("<br>", "<br />")
                .replace("</br>", "<br />")
                .replace("<hr>", "<hr />")
                .replaceAll("<a([^>]*)></a>", "")
                .replaceAll(PADDED_LANGUAGE_PARAMETER, "$1$3$5");
        return unescapeInsidePlaceholders(html)
                .replace(ConfluenceTags.CDATA_PLACEHOLDER_START, "<![CDATA[")
                .replace(ConfluenceTags.CDATA_PLACEHOLDER_END, "]]>");
    }

    /**
     * Jsoup escaped everything it serialised, but content destined for a CDATA section has to go
     * out verbatim. Only the regions between the placeholders are unescaped, so escaping elsewhere
     * in the document is left intact.
     */
    private static String unescapeInsidePlaceholders(String html) {
        int start = html.indexOf(ConfluenceTags.CDATA_PLACEHOLDER_START);
        while (start > -1) {
            int end = html.indexOf(ConfluenceTags.CDATA_PLACEHOLDER_END, start);
            if (end > -1) {
                String prefix = html.substring(0, start) + ConfluenceTags.CDATA_PLACEHOLDER_START;
                String suffix = html.substring(end);
                String unescaped = html.substring(start + ConfluenceTags.CDATA_PLACEHOLDER_START.length(), end)
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&");
                html = prefix + unescaped + suffix;
            }
            start = html.indexOf(ConfluenceTags.CDATA_PLACEHOLDER_START, start + 1);
        }
        return html;
    }
}
