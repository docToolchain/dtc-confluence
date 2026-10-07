/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Recognises a code listing that was marked up as a two-row table and writes it as a listing block.
 *
 * <p>The shape it looks for is a caption row followed by a row holding {@code <pre><code>}, which is
 * how the corpus the upstream project converted encoded source examples. The caption is matched
 * literally against {@link #CAPTION}, so this converter only fires on documents produced by that
 * one tool chain; see the module NOTICE.</p>
 */
public class ListingConverter extends AbstractConverter {

    /**
     * The caption that marks the table as a listing. Inherited verbatim from upstream, French
     * included: it is the string that tool wrote, not a label this converter chose.
     */
    static final String CAPTION = "Exemple :";

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return findListingElement((Element) node) != null;
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        Element code = findListingElement((Element) node);

        sb.append("\n");
        sb.append("----\n");
        // getWholeText, not text: indentation and blank lines are the content of a listing.
        sb.append(code.textNodes().get(0).getWholeText());
        sb.append("\n");
        sb.append("----\n\n");
        return context;
    }

    /**
     * The {@code <code>} element of a listing table, or {@code null} if {@code table} is not one.
     *
     * <p>Every step of the expected shape is checked, because anything else is an ordinary table
     * that {@link TableConverter} must be left to handle.</p>
     */
    private Element findListingElement(Element table) {
        if (!"table".equals(table.tagName()) || table.children().size() != 1) {
            return null;
        }
        Element tbody = table.child(0);
        if (!"tbody".equals(tbody.tagName()) || tbody.children().size() != 2) {
            return null;
        }
        Element captionRow = tbody.child(0);
        if (!"tr".equals(captionRow.tagName()) || captionRow.children().size() != 1) {
            return null;
        }
        Element captionCell = captionRow.child(0);
        if (!"td".equals(captionCell.tagName()) || !CAPTION.equals(captionCell.text().trim())) {
            return null;
        }
        Element listingRow = tbody.child(1);
        if (!"tr".equals(listingRow.tagName()) || listingRow.children().size() != 1) {
            return null;
        }
        Element listingCell = listingRow.child(0);
        if (!"td".equals(listingCell.tagName()) || listingCell.children().size() != 1) {
            return null;
        }
        Element pre = listingCell.child(0);
        if (!"pre".equals(pre.tagName()) || pre.children().size() != 1) {
            return null;
        }
        Element code = pre.child(0);
        if (!"code".equals(code.tagName()) || code.textNodes().isEmpty()) {
            return null;
        }
        return code;
    }
}
