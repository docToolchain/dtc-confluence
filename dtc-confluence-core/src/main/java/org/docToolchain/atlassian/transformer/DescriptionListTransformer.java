package org.docToolchain.atlassian.transformer;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.nodes.Element;

/**
 * Turns description lists into tables, because Confluence storage format has no equivalent.
 *
 * <p>Terms become header cells and definitions ordinary ones. HTML allows a term to carry several
 * definitions and the other way round, so a group with unequal counts is laid out with a row span
 * on the shorter side.</p>
 */
public class DescriptionListTransformer {

    private static final String TERM_CELL = "th";
    private static final String DEFINITION_CELL = "td";

    public void transformDescriptionLists(Element body) {
        for (Element list : body.select("dl")) {
            transformList(list);
        }
    }

    private static void transformList(Element list) {
        // WHATWG allows wrapping dt/dd in divs; they carry no meaning here.
        list.select("div").forEach(Element::unwrap);

        for (Group group : groupsOf(list)) {
            appendRows(list, group);
        }
        list.wrap("<table></table>").unwrap();
    }

    /**
     * Collects terms and definitions that belong together. A term following a definition starts a
     * new group; usually that yields one term and one definition each.
     */
    private static List<Group> groupsOf(Element list) {
        List<Group> groups = new ArrayList<>();
        Group current = new Group();
        groups.add(current);
        for (Element child : list.select("dt, dd")) {
            boolean isTerm = "dt".equals(child.tagName());
            if (isTerm && !current.definitions.isEmpty()) {
                current = new Group();
                groups.add(current);
            }
            child.tagName(isTerm ? TERM_CELL : DEFINITION_CELL);
            (isTerm ? current.terms : current.definitions).add(child);
            child.remove();
        }
        return groups;
    }

    private static void appendRows(Element list, Group group) {
        int terms = group.terms.size();
        int definitions = group.definitions.size();
        int rowspan = Math.abs(terms - definitions) + 1;
        // The shorter side gets the row span, on its last cell.
        int termSpanAt = terms < definitions ? terms - 1 : -1;
        int definitionSpanAt = terms < definitions ? -1 : definitions - 1;

        for (int index = 0; index < Math.max(terms, definitions); index++) {
            Element row = list.appendElement("tr");
            appendCell(row, group.terms, index, TERM_CELL, termSpanAt, rowspan);
            appendCell(row, group.definitions, index, DEFINITION_CELL, definitionSpanAt, rowspan);
        }
    }

    private static void appendCell(Element row, List<Element> cells, int index, String tagName,
                                   int spanAt, int rowspan) {
        if (cells.size() > index) {
            Element cell = cells.get(index);
            row.appendChild(cell);
            if (index == spanAt && rowspan > 1) {
                cell.attr("rowspan", String.valueOf(rowspan));
            }
        } else if (index == 0) {
            // nothing on this side at all: one empty cell spanning the whole group
            row.appendElement(tagName).attr("rowspan", String.valueOf(rowspan));
        }
    }

    private static final class Group {
        private final List<Element> terms = new ArrayList<>();
        private final List<Element> definitions = new ArrayList<>();
    }
}
