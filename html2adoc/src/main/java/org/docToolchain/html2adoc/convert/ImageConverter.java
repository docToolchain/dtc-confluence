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
        boolean imageBloc = parent != null && isNotInsideATable(parent) && opensItsOwnLine(element, sb);
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

    /**
     * Whether the image begins a line of the output, which is what decides the two forms:
     * {@code image::} is a block image and has to stand at the start of a line, {@code image:}
     * is inline and follows the words it stands among.
     *
     * <p>A Confluence image block arrives as {@code div.imageblock > div.content > img} among the
     * other blocks of a page. {@link TransparentConverter} drops both divs, so the image stands
     * where they stood - at the start of a line, after whatever block precedes it. Written as an
     * inline image it is indented by the space that separates one from preceding words, and an
     * indented line is a literal block: the page then prints the markup instead of showing the
     * image.</p>
     *
     * <p>A paragraph is asked rather than the output, because a paragraph states it: an image
     * alone in a {@code <p>} is a block image, and one sharing the paragraph with anything else -
     * a second image, a word - is not, however the line happens to have broken.</p>
     */
    private boolean opensItsOwnLine(Element image, StringBuilder sb) {
        Element element = image;
        Element parent = element.parent();
        while (parent != null
                && TransparentConverter.TAG_NAMES.contains(parent.tagName())
                && holdsOnly(parent, element)) {
            element = parent;
            parent = element.parent();
        }
        if (parent == null) {
            return false;
        }
        if ("p".equals(parent.tagName())) {
            return holdsOnly(parent, element);
        }
        return "body".equals(parent.tagName())
                || sb.length() == 0
                || AdocText.lastCharacterOf(sb) == '\n';
    }

    /** Whether {@code parent} holds {@code only} and, apart from it, nothing but empty space. */
    private boolean holdsOnly(Element parent, Element only) {
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode) {
                if (!isBlankOrNoBreakSpace(((TextNode) child).text())) {
                    return false;
                }
            } else if (child != only) {
                return false;
            }
        }
        return true;
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
