/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.Set;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * {@code <p>} becomes its content followed by a blank line.
 *
 * <p>A paragraph holding nothing but whitespace or {@code &nbsp;} is dropped: word processors that
 * export HTML use it for vertical spacing, which AsciiDoc has no equivalent of and does not need.</p>
 */
public class ParagraphConverter extends AbstractConverter {

    /** Elements whose own markup opens the line that their first paragraph belongs on. */
    private static final Set<String> OPENS_THE_LINE = Set.of("li", "td", "th");

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "p".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        Element element = (Element) node;
        String text = element.text();
        if (!element.children().isEmpty() || !isBlankOrNoBreakSpace(text)) {
            if (!continuesTheLineOfItsParent(element)) {
                startBlock(sb);
            }
            IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).create();
            Html2Adoc.convert(newContext, sb, node);
            sb.append("\n\n");
        }
        return context;
    }

    /**
     * Whether this paragraph is how HTML spells the first line of a list item or a table cell.
     *
     * <p>Both write markup that opens a line - the item's marker, the cell's separator - and
     * AsciiDoc reads the first line of their content from it. A paragraph that began a block there
     * would leave the marker alone on its line and put the text in a block of its own below, which
     * is an empty list item followed by a paragraph.</p>
     */
    private static boolean continuesTheLineOfItsParent(Element element) {
        Element parent = element.parent();
        if (parent == null || !OPENS_THE_LINE.contains(parent.tagName())) {
            return false;
        }
        for (Node sibling : parent.childNodes()) {
            if (sibling == element) {
                return true;
            }
            if (!(sibling instanceof TextNode) || !isBlankOrNoBreakSpace(((TextNode) sibling).text())) {
                return false;
            }
        }
        return false;
    }
}
