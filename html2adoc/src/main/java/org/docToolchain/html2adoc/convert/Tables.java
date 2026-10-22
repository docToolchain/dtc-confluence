/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Element;

/**
 * What the table converters need to work out about an HTML table, in one place because the table
 * and the cell both need most of it.
 *
 * <p>HTML says the shape of a table cell by cell; AsciiDoc needs the column count before the first
 * cell and marks a span on the cell that starts it. So the grid has to be measured before anything
 * is written.</p>
 */
final class Tables {

    /**
     * The cell separators, by nesting depth. AsciiDoc has exactly one alternative to {@code |}, so
     * a table inside a table inside a table cannot be written at all; the last entry is reused and
     * such a table comes out broken.
     */
    private static final char[] SEPARATORS = {'|', '!'};

    private static final String BLOCK_TAGS = "p|div|table|[uo]l|dl|dt|dd|pre|blockquote|hr|h[1-6]|figure";

    private Tables() {
    }

    /** How many tables enclose {@code element}, not counting {@code element} itself. */
    static int nestingDepth(Element element) {
        int depth = 0;
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if ("table".equals(parent.tagName())) {
                depth = depth + 1;
            }
        }
        return depth;
    }

    /** The character that separates the cells of a table nested {@code depth} tables deep. */
    static char separator(int depth) {
        return SEPARATORS[Math.min(Math.max(depth, 0), SEPARATORS.length - 1)];
    }

    /**
     * The separators that text inside {@code cell} has to escape: the one of its own table and the
     * ones of every table around it.
     *
     * <p>A {@code |} inside a nested table still ends the cell of the outer table, so a cell two
     * tables deep has to escape both characters.</p>
     */
    static String separatorsInForce(Element cell) {
        StringBuilder separators = new StringBuilder();
        for (int depth = 0; depth < nestingDepth(cell); depth++) {
            char separator = separator(depth);
            if (separators.indexOf(String.valueOf(separator)) < 0) {
                separators.append(separator);
            }
        }
        return separators.toString();
    }

    /**
     * The rows of {@code table}, in document order, across {@code thead}, {@code tbody} and
     * {@code tfoot} but not across a nested table, whose rows belong to that table.
     */
    static List<Element> rows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element tr : table.getElementsByTag("tr")) {
            if (enclosingTable(tr) == table) {
                rows.add(tr);
            }
        }
        return rows;
    }

    /** The {@code td} and {@code th} children of a row. */
    static List<Element> cells(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element child : row.children()) {
            if ("td".equals(child.tagName()) || "th".equals(child.tagName())) {
                cells.add(child);
            }
        }
        return cells;
    }

    /**
     * The value of {@code colspan} or {@code rowspan}, which is 1 unless the HTML widens the cell.
     *
     * <p>Anything unusable — absent, not a number, or the {@code rowspan="0"} that HTML defines as
     * "to the end of the section" and this converter does not implement — counts as 1, because a
     * cell always covers at least itself and a wrong span would shift every later cell.</p>
     */
    static int span(Element cell, String attributeName) {
        try {
            return Math.max(Integer.parseInt(cell.attr(attributeName).trim()), 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * A width an author declared on a column, as an attribute or inside a style.
     *
     * <p>A decimal is read and then rounded, because AsciiDoc's colspec grammar takes integers
     * only: a single decimal makes the whole specification unparsable, and asciidoctor then takes
     * the column count from the first line of the table instead - one cell, because this converter
     * writes one cell per line. A 33.3333/66.6667 table, which is what asciidoctor's own HTML for
     * {@code cols="1,2"} says, came out as a single column with every cell on a row of its own.</p>
     */
    private static final Pattern DECLARED_WIDTH =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(%?)\\s*(?:!important)?");

    /**
     * The column widths the table declares, in order, or an empty list where it declares none.
     *
     * <p>A {@code <colgroup>} is how HTML states that a table is not evenly divided, and
     * {@code cols="2*"} throws that away: a 30/70 table came out 50/50. Only taken where every
     * column of the table declares one, because a partial list would be read as the whole shape
     * and the columns left out would vanish.</p>
     */
    static List<String> declaredWidths(Element table) {
        // Only a direct child: jsoup's table insertion mode reparents a colgroup to the table
        // whatever the markup put it in - before thead, after it, inside thead or inside tbody.
        List<Element> columns = table.select("> colgroup > col");
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> widths = new ArrayList<>();
        for (Element column : columns) {
            String width = widthOf(column);
            if (width.isEmpty()) {
                return List.of();
            }
            // <col span="2"> declares one width for that many columns, and the list has to carry
            // one entry per column or it would be read as the whole shape of a narrower table.
            for (int covered = span(column, "span"); covered > 0; covered--) {
                widths.add(width);
            }
        }
        return widths.size() == columnCount(table) ? widths : List.of();
    }

    /**
     * @return the declared width of one column, {@code "30%"} or {@code "30"}, or empty
     */
    private static String widthOf(Element column) {
        // The style first: a CSS width overrides the presentational attribute in a browser, so
        // reading the attribute first would state the opposite of what a reader sees.
        String style = styleWidth(column.attr("style"));
        String declaration = style.isEmpty() ? column.attr("width") : style;
        Matcher matcher = DECLARED_WIDTH.matcher(declaration.trim());
        if (!matcher.matches()) {
            return "";
        }
        return Math.round(Double.parseDouble(matcher.group(1))) + matcher.group(2);
    }

    private static String styleWidth(String style) {
        for (String declaration : style.split(";")) {
            String[] property = declaration.split(":", 2);
            // Lower case: CSS property names are case-insensitive, and WIDTH: 30% is a width.
            if (property.length == 2
                    && "width".equals(property[0].trim().toLowerCase(Locale.ROOT))) {
                return property[1].trim();
            }
        }
        return "";
    }

    /**
     * How many columns wide {@code table} is: the widest of its rows, counting both the columns a
     * cell covers itself and those a {@code rowspan} from an earlier row has already taken.
     */
    static int columnCount(Element table) {
        List<Element> rows = rows(table);
        int[] takenByRowspan = new int[rows.size()];
        int columns = 0;
        for (int row = 0; row < rows.size(); row++) {
            int width = takenByRowspan[row];
            for (Element cell : cells(rows.get(row))) {
                int colspan = span(cell, "colspan");
                width = width + colspan;
                int coveredUntil = Math.min(row + span(cell, "rowspan"), rows.size());
                for (int covered = row + 1; covered < coveredUntil; covered++) {
                    takenByRowspan[covered] = takenByRowspan[covered] + colspan;
                }
            }
            columns = Math.max(columns, width);
        }
        return columns;
    }

    /** Whether {@code element} has a child that AsciiDoc writes as a block rather than inline. */
    static boolean hasBlockChild(Element element) {
        for (Element child : element.children()) {
            if (child.tagName().matches(BLOCK_TAGS)) {
                return true;
            }
        }
        return false;
    }

    /** The innermost table {@code element} sits in, or {@code null} if it sits in none. */
    private static Element enclosingTable(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if ("table".equals(parent.tagName())) {
                return parent;
            }
        }
        return null;
    }
}
