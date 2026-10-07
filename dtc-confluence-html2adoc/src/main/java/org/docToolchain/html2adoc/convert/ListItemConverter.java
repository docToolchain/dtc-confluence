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

/** {@code <li>} becomes {@code *} or {@code .}, taken from the list recorded in the context. */
public class ListItemConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "li".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        if (context.getListType() == null) {
            throw new IllegalStateException("A list item outside any list");
        }
        switch (context.getListType()) {
            case UL:
                sb.append("*");
                break;
            case OL:
                sb.append(".");
                break;
            default:
                throw new IllegalStateException("Unexpected list type: " + context.getListType());
        }
        sb.append(" ");
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).withLineStarted(true).create();
        Html2Adoc.convert(newContext, sb, node);
        sb.append("\n");
        return context;
    }
}
