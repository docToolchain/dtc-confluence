package org.docToolchain.atlassian.confluence.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a walk of a Confluence page tree found, in the shape the converter reads.
 *
 * <p>The maps are keyed by page and attachment id and carry exactly the fields the converter looks
 * for. They are plain maps rather than types of their own because the converter, moved unchanged
 * from docToolchain, reads them by name.</p>
 */
public class ExportedTree {

    /** Page id to its title, parent, file names, position and contributors. */
    private final Map<String, Map<String, Object>> pages = new LinkedHashMap<>();

    /** Attachment id to its file name, version, page and download link. */
    private final Map<String, Map<String, Object>> attachments = new LinkedHashMap<>();

    /** Page id to its storage format, as Confluence stores it. */
    private final Map<String, String> bodies = new LinkedHashMap<>();

    /** Page id to the ids of its children, in the order Confluence returned them. */
    private final Map<String, List<String>> childrenByParent = new LinkedHashMap<>();

    /** Pages that were named but could not be read, so a caller can report them. */
    private final List<String> unreadable = new ArrayList<>();

    /** The space the root page lives in: name, key, and the root as its home page. */
    private Map<String, Object> space = new LinkedHashMap<>();

    public Map<String, Map<String, Object>> getPages() {
        return pages;
    }

    public Map<String, Map<String, Object>> getAttachments() {
        return attachments;
    }

    /**
     * The body is kept here rather than in the page map because the page map is what the converter
     * reads, and it reads it once per page for every page - carrying a whole document in each
     * entry of it would be paid for on every lookup.
     */
    public Map<String, String> getBodies() {
        return bodies;
    }

    public Map<String, List<String>> getChildrenByParent() {
        return childrenByParent;
    }

    public List<String> getUnreadable() {
        return unreadable;
    }

    public Map<String, Object> getSpace() {
        return space;
    }

    public void setSpace(Map<String, Object> space) {
        this.space = space;
    }
}
