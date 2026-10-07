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
 * {@code <table>} opens and closes the AsciiDoc table, and states its shape up front.
 *
 * <p>AsciiDoc wants the column count and the header flag before the first row, so the two have to
 * be read ahead of writing anything. The table and not {@code <tbody>} owns the delimiters because
 * a {@code <thead>} precedes the body: a table opened by the body would leave the header row above
 * the table instead of in it.</p>
 *
 * <p>The column count is the width of the widest row and not the cell count of the first, because a
 * {@code colspan} makes one cell cover several columns and a {@code rowspan} leaves a later row one
 * cell short of them; see {@link Tables#columnCount}.</p>
 *
 * <p>A table inside a cell is delimited with {@code !} rather than {@code |}, which is how AsciiDoc
 * tells an inner table's cells from the outer one's.</p>
 */
public class TableConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            Element element = (Element) node;
            // A table without a cell has no shape to state, and `[cols="0*"]` is not a shape;
            // left alone, the fallback of Html2Adoc keeps whatever text it holds.
            return "table".equals(element.tagName()) && Tables.columnCount(element) > 0;
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        startBlock(sb);
        Element table = (Element) node;
        String delimiter = Tables.separator(Tables.nestingDepth(table)) + "===";
        sb.append("[cols=\"").append(Tables.columnCount(table)).append("*\"");
        if (isHeaderRow(Tables.rows(table).get(0))) {
            sb.append(", options=\"header\"");
        }
        sb.append("]\n");
        sb.append(delimiter);
        sb.append("\n");
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).create();
        Html2Adoc.convert(newContext, sb, node);
        sb.append(delimiter);
        sb.append("\n");
        return context;
    }

    /**
     * Whether {@code row} is the header of its table.
     *
     * <p>Standing in a {@code <thead>} says so outright. Without one, a row of nothing but
     * {@code <th>} is the same statement made with cell types, which is how hand-written and
     * exported HTML alike mark a header when they omit the section elements.</p>
     */
    private static boolean isHeaderRow(Element row) {
        Element parent = row.parent();
        if (parent != null && "thead".equals(parent.tagName())) {
            return true;
        }
        int cells = 0;
        for (Element cell : row.children()) {
            if ("td".equals(cell.tagName())) {
                return false;
            }
            if ("th".equals(cell.tagName())) {
                cells = cells + 1;
            }
        }
        return cells > 0;
    }
}
