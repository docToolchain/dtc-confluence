package org.docToolchain.atlassian.transformer;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;

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
        // Innermost first. The macro is built from details.html(), so a nested block has to be a
        // macro already by the time its parent is read - otherwise it would be copied in as raw
        // details markup and then skipped, because removing the parent detaches it. Reverse
        // document order gives that for free: a descendant always starts after its ancestor.
        Elements collapsibles = body.select("details");
        for (int i = collapsibles.size() - 1; i >= 0; i--) {
            Element details = collapsibles.get(i);
            Element summary = details.selectFirst("summary");
            String title = DEFAULT_TITLE;
            if (summary != null) {
                // A summary can be present and say nothing. An expand macro with an empty title
                // is a bar with no label, so the default stands in for that too.
                if (!summary.text().isBlank()) {
                    title = summary.text();
                }
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
