package org.docToolchain.atlassian.confluence.export

/**
 * What a walk of a Confluence page tree found, in the shape the converter reads.
 *
 * <p>The maps are keyed by page and attachment id and carry exactly the fields
 * {@link ConfluenceConverter} looks for. They are plain maps rather than types of their own
 * because the converter, moved unchanged from docToolchain, reads them by name.</p>
 */
class ExportedTree {

    /** Page id to its title, parent, file names, position and contributors. */
    Map<String, Map> pages = [:]

    /** Attachment id to its file name, version, page and download link. */
    Map<String, Map> attachments = [:]

    /** The space the root page lives in: name, key, and the root as its home page. */
    Map space = [:]

    /** Page id to the ids of its children, in the order Confluence returned them. */
    Map<String, List<String>> childrenByParent = [:]

    /** Pages that were named but could not be read, so a caller can report them. */
    List<String> unreadable = []
}
