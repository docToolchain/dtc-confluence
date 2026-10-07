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

/** {@code <h1>} to {@code <h6>} become one to six {@code =}, with the anchor, if any, above. */
public class HeaderConverter extends AbstractConverter {

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
        sb.append("=".repeat(count));
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
