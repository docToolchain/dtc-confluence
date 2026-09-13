package org.docToolchain.atlassian.confluence.page;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The pages derived from one document, together with the anchors found along the way.
 *
 * <p>Anchors map an element id to the title of the page it ended up on, so that a cross-reference
 * can be rewritten into a link to that page. {@code pageAnchors} holds the same for section
 * headings, which name a page rather than a position within one.</p>
 */
public class PageTree {

    private final List<Page> pages;
    private final Map<String, String> anchors;
    private final Map<String, String> pageAnchors;

    PageTree(List<Page> pages, Map<String, String> anchors, Map<String, String> pageAnchors) {
        this.pages = pages;
        this.anchors = anchors;
        this.pageAnchors = pageAnchors;
    }

    public List<Page> getPages() {
        return Collections.unmodifiableList(pages);
    }

    public Map<String, String> getAnchors() {
        return Collections.unmodifiableMap(anchors);
    }

    public Map<String, String> getPageAnchors() {
        return Collections.unmodifiableMap(pageAnchors);
    }
}
