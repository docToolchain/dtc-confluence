package org.docToolchain.atlassian.transformer;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * Turns a collapsible block into the Confluence expand macro.
 *
 * <p>AsciiDoc marks a block collapsible with {@code [%collapsible]} and AsciiDoctor renders that as
 * {@code details} with a {@code summary}. Confluence has no such element; what it has is the expand
 * macro, which folds the same way.</p>
 *
 * <p>arc42 templates lean on collapsible blocks for guidance text, so without this the hints they
 * mean to hide stand open on the page.</p>
 */
public class CollapsibleTransformer {

    private static final String DEFAULT_TITLE = "Details";

    public void transformCollapsibles(Element body) {
        for (Element details : body.select("details")) {
            Element summary = details.selectFirst("summary");
            String title = summary == null ? DEFAULT_TITLE : summary.text();
            if (summary != null) {
                summary.remove();
            }

            // A Confluence expand macro is always folded, so [%collapsible%open] loses its initial
            // state. The fold itself matters more than starting open.
            details.before("<ac:structured-macro ac:name=\"expand\">"
                    + "<ac:parameter ac:name=\"title\">" + Entities.escape(title) + "</ac:parameter>"
                    + "<ac:rich-text-body>" + details.html() + "</ac:rich-text-body>"
                    + "</ac:structured-macro>");
            details.remove();
        }
    }
}
