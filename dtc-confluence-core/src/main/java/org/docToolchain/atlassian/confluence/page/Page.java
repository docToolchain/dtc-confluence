package org.docToolchain.atlassian.confluence.page;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.nodes.Element;

/**
 * One Confluence page as derived from the document, before anything is sent.
 *
 * <p>The body is still HTML at this point; converting it to storage format happens on the way out.
 * Children are the pages that will be created beneath this one.</p>
 */
public class Page {

    private String title;
    private final Element body;
    private final List<Page> children = new ArrayList<>();
    private String parent;

    public Page(String title, Element body, String parent) {
        this.title = title;
        this.body = body;
        this.parent = parent;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Element getBody() {
        return body;
    }

    public List<Page> getChildren() {
        return children;
    }

    /**
     * @return the id of the Confluence page this one hangs under, or {@code null} for a page at the
     *         root of the space
     */
    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    @Override
    public String toString() {
        return "Page[" + title + ", " + children.size() + " children, parent=" + parent + "]";
    }
}
