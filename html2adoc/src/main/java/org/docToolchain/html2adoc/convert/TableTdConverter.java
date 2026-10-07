/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.List;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * {@code <td>} and {@code <th>} both become one cell on a line of its own.
 *
 * <p>HTML puts everything about a cell inside it; AsciiDoc puts three of those things in a cell
 * specifier in front of the separator, and all three are written here:</p>
 *
 * <ul>
 *   <li>a {@code colspan} as {@code 2+}, a {@code rowspan} as {@code .2+}, both as {@code 2.2+};</li>
 *   <li>the {@code a} style for a cell whose content is blocks and not one line of text, because
 *       the default style parses a cell as a single paragraph and would print a nested list or
 *       table as literal text;</li>
 *   <li>nothing at all for the ordinary cell, which keeps the output readable.</li>
 * </ul>
 *
 * <p>An empty cell still writes its separator. Leaving it out would not produce an empty cell, it
 * would produce a row one cell short, and AsciiDoc would fill the gap from the next row.</p>
 *
 * <p>The cell is written into the output as it is converted, rather than into a buffer of its own,
 * so that the text inside it can see what stands in front of it: a {@code .} is a block title after
 * the newline of a block cell and ordinary text after the {@code |} of a plain one, and
 * {@link AdocText} can only tell the two apart from the character already written.</p>
 */
public class TableTdConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            String tagName = ((Element) node).tagName();
            return "td".equals(tagName) || "th".equals(tagName);
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        Element cell = (Element) node;
        char separator = Tables.separator(Tables.nestingDepth(cell) - 1);
        Element contentRoot = unwrapSingleParagraph(cell);
        boolean blockContent = Tables.hasBlockChild(contentRoot);

        sb.append(spanSpecifier(cell));
        if (blockContent) {
            sb.append('a');
        }
        sb.append(separator);
        if (blockContent) {
            // The blocks start on the line below the separator, and at column zero: indenting them
            // would turn every paragraph in the cell into a literal block.
            sb.append('\n');
        }
        int contentStart = sb.length();
        // A cell starts a line of its own, so nothing is owed to its first character.
        IContext cellContext = ContextBuilder.build(context)
                .withSpaceNeeded(false)
                .withLineStarted(false)
                .withCellSeparators(Tables.separatorsInForce(cell))
                .create();
        Html2Adoc.convert(cellContext, sb, contentRoot);
        trimNewlines(sb, contentStart);
        sb.append('\n');
        // The cell ended the line, so the next cell starts one: the position is the one we got.
        return context;
    }

    /**
     * The cell specifier for the cell's spans, or the empty string when it covers one column of one
     * row, which is most cells.
     */
    private static String spanSpecifier(Element cell) {
        int colspan = Tables.span(cell, "colspan");
        int rowspan = Tables.span(cell, "rowspan");
        if (colspan == 1 && rowspan == 1) {
            return "";
        }
        StringBuilder specifier = new StringBuilder();
        if (colspan > 1) {
            specifier.append(colspan);
        }
        if (rowspan > 1) {
            specifier.append('.').append(rowspan);
        }
        return specifier.append('+').toString();
    }

    /**
     * The node whose children are the cell's content: the cell itself, or the one paragraph that
     * wraps all of it.
     *
     * <p>Confluence wraps the content of every cell in a {@code <p class="tableblock">}. Taken at
     * face value that paragraph is block content and would make an {@code a|} cell of every cell in
     * every exported table; it is in truth only how Confluence spells a cell. A cell holding
     * <em>two</em> paragraphs is a different matter — that one really is blocks.</p>
     */
    private static Element unwrapSingleParagraph(Element cell) {
        List<Node> content = contentNodes(cell);
        if (content.size() == 1 && content.get(0) instanceof Element) {
            Element only = (Element) content.get(0);
            if ("p".equals(only.tagName())) {
                return only;
            }
        }
        return cell;
    }

    /** The children of {@code element} that carry content, which the whitespace between tags does not. */
    private static List<Node> contentNodes(Element element) {
        return element.childNodes().stream()
                .filter(node -> !(node instanceof TextNode) || !isBlankOrNoBreakSpace(((TextNode) node).text()))
                .toList();
    }

    /**
     * Removes the newlines at either end of the cell content, which starts at {@code contentStart}.
     *
     * <p>Only newlines: a leading space can be the indentation of a literal block, and the one a
     * block converter left behind at the end would otherwise be written past the end of the cell.</p>
     */
    private static void trimNewlines(StringBuilder sb, int contentStart) {
        while (sb.length() > contentStart && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        int start = contentStart;
        while (start < sb.length() && sb.charAt(start) == '\n') {
            start = start + 1;
        }
        sb.delete(contentStart, start);
    }
}
