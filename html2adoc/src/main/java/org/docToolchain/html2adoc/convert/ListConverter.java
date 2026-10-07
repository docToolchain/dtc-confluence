/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.docToolchain.html2adoc.context.ListType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * {@code <ul>} and {@code <ol>} write no marker of their own; they only record which kind of list
 * the items inside are in, because AsciiDoc marks that on every item.
 */
public class ListConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return ((Element) node).tagName().matches("[uo]l");
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        startBlock(sb);
        Element element = (Element) node;
        ListType listType;
        if ("ul".equals(element.tagName())) {
            listType = ListType.UL;
        } else if ("ol".equals(element.tagName())) {
            listType = ListType.OL;
        } else {
            throw new IllegalStateException("Unexpected list type: " + element.tagName());
        }
        IContext newContext = ContextBuilder.build(context).withListType(listType).withSpaceNeeded(false).create();
        Html2Adoc.convert(newContext, sb, node);
        sb.append("\n");
        return context;
    }
}
