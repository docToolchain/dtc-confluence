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
        // A blank line in front of every list, nested ones included. Tried the other way, to
        // make the source read as the list it is, and reverted: asciidoctor nests a list after a
        // blank line either way, so on the shape this converter actually meets - Confluence wraps
        // each item's content in a <p>, which has already written the blank line - it changed
        // nothing. Where it did change something it destroyed it: an <li> holding only a nested
        // list writes "* " with nothing after it, which is not a list item, and the "**" markers
        // that followed without a blank line were swallowed into that paragraph as bold.
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
        // One deeper than the list this one stands in, which is what tells a nested item from
        // an item of the outer list. Written at the parent's depth, a <ul> inside an <li> became
        // another item of the list around it: three items in two lists came out as three in one.
        IContext newContext = ContextBuilder.build(context)
                .withListType(listType)
                .withListDepth(context.getListDepth() + 1)
                .withSpaceNeeded(false)
                .create();
        Html2Adoc.convert(newContext, sb, node);
        sb.append("\n");
        return context;
    }
}
