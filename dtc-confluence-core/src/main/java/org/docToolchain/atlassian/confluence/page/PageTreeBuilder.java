package org.docToolchain.atlassian.confluence.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * Turns an AsciiDoctor HTML document into the tree of Confluence pages it should become.
 *
 * <p>AsciiDoctor nests sections as {@code div.sect2} inside {@code div.sect1}, and so on.
 * {@code maxLevel} decides how deep that nesting is turned into separate pages: zero puts
 * everything on one page, one gives a page per top-level section, two also splits sub-sections.
 * Sections deeper than that stay inline, with their headings promoted so the page reads as if it
 * had always started there.</p>
 */
public class PageTreeBuilder {

    /** AsciiDoctor emits headings up to h7. */
    private static final int DEEPEST_HEADING = 7;

    public PageTree build(Document dom, String parentId, int maxLevel) {
        Map<String, String> anchors = new LinkedHashMap<>();
        Map<String, String> pageAnchors = new LinkedHashMap<>();
        List<Page> pages = new ArrayList<>();
        String title = dom.select("h1").text();
        // Has to happen before any page body is taken out of the document: the definitions sit
        // outside div#content and would otherwise be dropped along with the rest of the document.
        Footnotes footnotes = Footnotes.extractFrom(dom);

        if (maxLevel <= 0) {
            for (Element pageBody : dom.select("div#content")) {
                pageBody.select("div.sect2").unwrap();
                promoteHeaders(pageBody, 2, 1);
                Page page = new Page(title, pageBody, parentId);
                pages.add(page);
                parentId = null;
                footnotes.appendTo(pageBody);
                anchors.putAll(parseAnchors(page));
            }
            return new PageTree(pages, anchors, pageAnchors);
        }

        // The preamble - everything above the first section - becomes the page the rest hangs under.
        for (Element pageBody : dom.select("div#preamble div.sectionbody")) {
            pageBody.select("div.sect2").unwrap();
            Page preamble = new Page(title, pageBody, parentId);
            pages.add(preamble);
            parentId = null;
            footnotes.appendTo(pageBody);
            anchors.putAll(parseAnchors(preamble));
            preamble.getChildren()
                    .addAll(pagesOfSections(dom, parentId, anchors, pageAnchors, 1, maxLevel, footnotes));
        }

        if (pages.isEmpty()) {
            pages.addAll(pagesOfSections(dom, parentId, anchors, pageAnchors, 1, maxLevel, footnotes));
        }
        return new PageTree(pages, anchors, pageAnchors);
    }

    private List<Page> pagesOfSections(Element element, String parentId, Map<String, String> anchors,
                                       Map<String, String> pageAnchors, int level, int maxLevel,
                                       Footnotes footnotes) {
        List<Page> pages = new ArrayList<>();
        for (Element section : element.select("div.sect" + level)) {
            // The section's own heading, not every heading of that level below it: Elements.text()
            // would concatenate them while attr("id") took only the first, which is inconsistent.
            Element heading = section.selectFirst("h" + (level + 1));
            pageAnchors.putAll(recordPageAnchor(heading));

            Element pageBody;
            Element sectionBody = level == 1 ? section.selectFirst("div.sectionbody") : null;
            if (sectionBody != null) {
                pageBody = sectionBody.clone();
            } else {
                // Work on a clone, so the original document is left alone and whitespace survives.
                // This also covers a level-1 section without a div.sectionbody, which would
                // otherwise have no body at all.
                pageBody = section.clone();
                pageBody.select("h" + (level + 1)).remove();
            }

            Page currentPage = new Page(heading == null ? "" : heading.text(), pageBody, parentId);
            if (maxLevel > level) {
                currentPage.getChildren()
                        .addAll(pagesOfSections(section, null, anchors, pageAnchors,
                                level + 1, maxLevel, footnotes));
                // the nested sections became pages of their own, so drop them from this body
                pageBody.select("div.sect" + (level + 1)).remove();
            } else {
                // they stay here, but without the wrapper
                pageBody.select("div.sect" + (level + 1)).unwrap();
            }
            promoteHeaders(pageBody, level + 2, level + 1);
            pages.add(currentPage);
            // After the nested sections were taken out, so a footnote lands on the page that
            // actually shows its reference rather than on the ancestor it was cut from.
            footnotes.appendTo(pageBody);
            anchors.putAll(parseAnchors(currentPage));
        }
        return pages;
    }

    /**
     * Records every element id on the page and replaces it with a Confluence anchor macro, so that
     * a cross-reference can find it again.
     *
     * @return the ids found, each mapped to the title of the page carrying it
     */
    static Map<String, String> parseAnchors(Page page) {
        Map<String, String> anchors = new LinkedHashMap<>();
        for (Element anchor : page.getBody().select("[id]")) {
            String name = anchor.attr("id");
            anchors.put(name, page.getTitle());
            // The id goes into markup, so it has to be escaped: a hand-written document may
            // carry an id that AsciiDoctor would never generate.
            anchor.before("<ac:structured-macro ac:name=\"anchor\">"
                    + "<ac:parameter ac:name=\"\">" + Entities.escape(name) + "</ac:parameter>"
                    + "</ac:structured-macro>");
        }
        return anchors;
    }

    /**
     * @return the id of the section heading mapped to its text, or nothing if the heading carries
     *         no id
     */
    static Map<String, String> recordPageAnchor(Element heading) {
        if (heading == null) {
            return Map.of();
        }
        String id = heading.attr("id");
        return id.isEmpty() ? Map.of() : Map.of(id, heading.text());
    }

    /**
     * Lifts headings by {@code offset} levels, so that a section which became its own page reads
     * as a document in its own right.
     */
    static void promoteHeaders(Element body, int start, int offset) {
        for (int i = start; i <= DEEPEST_HEADING; i++) {
            body.select("h" + i).tagName("h" + (i - offset)).before("<br />");
        }
    }
}
