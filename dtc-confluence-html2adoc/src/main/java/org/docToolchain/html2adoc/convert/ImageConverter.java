/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * {@code <img>} becomes a block image ({@code image::}) or an inline one ({@code image:}).
 *
 * <p>HTML does not distinguish the two; the position does. An image that is the only content of its
 * paragraph, or sits directly in the body, was meant as a figure. One standing between words, or
 * inside a table cell where a block image would break the cell, is inline.</p>
 */
public class ImageConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "img".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        Element element = (Element) node;
        Element parent = element.parent();
        boolean imageBloc = parent != null
                && (("p".equals(parent.tagName()) && hasNoOtherChild(parent)) || "body".equals(parent.tagName()))
                && isNotInsideATable(parent);
        if (imageBloc) {
            sb.append("\n");
            sb.append("image::");
        } else {
            sb.append(" image:");
        }
        sb.append(element.attr("src"));
        sb.append("[]");
        if (imageBloc) {
            sb.append("\n");
            if ("body".equals(parent.tagName())) {
                // No paragraph converter will follow to close the block, so do it here.
                sb.append("\n");
            }
        } else {
            sb.append(" ");
        }
        return context;
    }

    private boolean isNotInsideATable(Element parent) {
        Element e = parent;
        while (e != null) {
            if ("table".equals(e.tagName())) {
                return false;
            }
            e = e.parent();
        }
        return true;
    }

    /** Whether {@code parent} holds exactly this one image and, apart from it, only empty space. */
    private boolean hasNoOtherChild(Element parent) {
        if (parent.childNodeSize() == 1) {
            return true;
        }
        int images = 0;
        for (Node n : parent.childNodes()) {
            if (n instanceof TextNode) {
                if (!isBlankOrNoBreakSpace(((TextNode) n).text())) {
                    return false;
                }
            } else if (n instanceof Element) {
                if (!"img".equals(((Element) n).tagName())) {
                    return false;
                }
                images = images + 1;
            }
        }
        return images == 1;
    }
}
