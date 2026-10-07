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

/** {@code <tr>} becomes the blank line that separates two rows of an AsciiDoc table. */
public class TableTrConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "tr".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        sb.append("\n");
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).create();
        Html2Adoc.convert(newContext, sb, node);
        return context;
    }
}
