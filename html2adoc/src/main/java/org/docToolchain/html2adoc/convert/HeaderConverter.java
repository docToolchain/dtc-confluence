/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * {@code <h1>} to {@code <h6>} become {@code =} markers for their depth plus the context's heading
 * shift, with the anchor, if any, above.
 */
public class HeaderConverter extends AbstractConverter {

    /** Six markers is level 5, the deepest section AsciiDoc has. */
    private static final int MAX_MARKERS = 6;

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return ((Element) node).tagName().matches("h[1-6]");
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        startBlock(sb);
        Element element = (Element) node;
        Element aNameElement = findANameElement(element);
        if (aNameElement != null) {
            sb.append("[[");
            sb.append(aNameElement.attr("name"));
            sb.append("]]");
            sb.append("\n");
        }
        int count = Integer.parseInt(element.tagName().substring(1));
        // Shifted, because <h1> is the top heading of a fragment and not necessarily the title of
        // the document the fragment is written into. Clamped at both ends: AsciiDoc has levels 0
        // to 5, a seventh "=" is not a deeper section but a paragraph beginning with one, and no
        // marker at all would leave the line starting with the space below - a literal block.
        // The upper clamp loses structure where a page is deeper than the levels that are left:
        // two HTML levels then share one AsciiDoc level and become siblings.
        sb.append("=".repeat(Math.min(Math.max(count + context.getHeadingShift(), 1), MAX_MARKERS)));
        sb.append(" ");
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).withLineStarted(true).create();
        Html2Adoc.convert(newContext, sb, node);
        sb.append("\n\n");
        return context;
    }

    /**
     * The {@code <a name="...">} that generated HTML puts inside a heading to anchor it, if the
     * heading has one. AsciiDoc writes the anchor above the heading instead, so it has to be found
     * before the heading text is converted.
     */
    private Element findANameElement(Element element) {
        for (Element e : element.children()) {
            if ("a".equals(e.tagName()) && !e.attr("name").isEmpty()) {
                return e;
            }
        }
        return null;
    }
}
