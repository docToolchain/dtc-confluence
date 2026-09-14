package org.docToolchain.atlassian.confluence.page;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The footnote definitions of a document, moved to the pages that refer to them.
 *
 * <p>AsciiDoctor renders a footnote as a reference in the text plus a definition collected at the
 * end of the document, in {@code div#footnotes}. That div is a sibling of {@code div#content}, not
 * a child, so page splitting never saw it: the references were published pointing at definitions
 * that had been left behind, and clicking one did nothing.</p>
 *
 * <p>A definition is placed on the first page that refers to it, in the order the pages are built.
 * References on later pages then resolve through the anchor map and become links to that page, so
 * a definition is never duplicated and the numbering AsciiDoctor assigned keeps its meaning across
 * the whole page tree.</p>
 */
class Footnotes {

    /** The id AsciiDoctor gives a footnote definition, and what a reference links to. */
    private static final String DEFINITION_ID_PREFIX = "_footnotedef_";

    private final Map<String, Element> definitions;
    private final Set<String> placed = new LinkedHashSet<>();

    private Footnotes(Map<String, Element> definitions) {
        this.definitions = definitions;
    }

    /**
     * Takes the definitions out of the document, so that page splitting cannot pick them up a
     * second time as part of some section's body.
     */
    static Footnotes extractFrom(Document dom) {
        Map<String, Element> definitions = new LinkedHashMap<>();
        for (Element definition : dom.select("div#footnotes div.footnote[id]")) {
            definitions.put(definition.attr("id"), definition);
        }
        dom.select("div#footnotes").remove();
        return new Footnotes(definitions);
    }

    /**
     * Appends the definitions this page is the first to refer to, in the order the references
     * appear. Does nothing to a page that refers to none, so no empty footnote block is published.
     */
    void appendTo(Element pageBody) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Element reference : pageBody.select("a[href^=#" + DEFINITION_ID_PREFIX + "]")) {
            String id = reference.attr("href").substring(1);
            if (definitions.containsKey(id) && !placed.contains(id)) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        Element block = pageBody.appendElement("div").addClass("footnotes");
        block.appendElement("hr");
        for (String id : wanted) {
            Element definition = definitions.get(id).clone();
            dropBacklinksWithoutTarget(definition, pageBody);
            block.appendChild(definition);
            placed.add(id);
        }
    }

    /**
     * Removes a definition's link back to its reference where that reference is not on this page.
     *
     * <p>A footnote first used in a section heading and referred to again in the body of another
     * page reaches this: the heading became a plain page title, so the reference it carried is
     * nowhere, and the back-link would be published pointing at an anchor that does not exist. The
     * number stays as text; only the link goes.</p>
     */
    private static void dropBacklinksWithoutTarget(Element definition, Element pageBody) {
        for (Element backlink : definition.select("a[href^=#]")) {
            String target = backlink.attr("href").substring(1);
            if (pageBody.getElementById(target) == null) {
                backlink.unwrap();
            }
        }
    }
}
