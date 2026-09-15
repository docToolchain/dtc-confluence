package org.docToolchain.atlassian.confluence.export

/**
 * Walks a Confluence page tree downwards from one root and collects what the converter needs.
 *
 * <p>Breadth first, so that a page's position among its siblings is the order Confluence returned
 * them in - which is the order the exported document is written in.</p>
 *
 * <p>A page that cannot be read is recorded and skipped rather than ending the export: a tree of a
 * few hundred pages usually holds one whose permissions differ, and losing the other pages over it
 * helps nobody.</p>
 */
class PageTreeWalker {

    private final ConfluenceReader reader
    private final ConfluenceConverter converter
    private final String stripPagePrefixRegex

    /**
     * @param stripPagePrefixRegex applied to the sanitised file name to shorten paths, or empty
     *                             to leave the names as they are
     */
    PageTreeWalker(ConfluenceReader reader, ConfluenceConverter converter,
                   String stripPagePrefixRegex = '') {
        this.reader = reader
        this.converter = converter
        this.stripPagePrefixRegex = stripPagePrefixRegex ?: ''
    }

    /**
     * @param rootPageId the page to start from; it and everything below it is collected
     * @return what was found, or throws if the root itself cannot be read
     */
    ExportedTree walk(String rootPageId) {
        def rootPage = reader.fetchPage(rootPageId)
        if (!rootPage) {
            throw new IllegalStateException(
                "Root page ${rootPageId} is not there, or not readable with these credentials")
        }

        def tree = new ExportedTree()
        def spaceInfo = rootPage.space ?: [:]
        tree.space = [name: spaceInfo.name ?: '', key: spaceInfo.key ?: '', homePage: rootPageId]

        def queue = new LinkedList<Map>()
        queue << [id: rootPageId, parentId: '0', position: 0]
        Set<String> visited = [] as Set

        while (!queue.isEmpty()) {
            def entry = queue.poll()
            String pageId = entry.id as String
            // A page can be reached twice only through a malformed tree, but reading it twice
            // would also queue its children twice, and that does not end.
            if (!visited.add(pageId)) {
                continue
            }

            def page = pageId == rootPageId ? rootPage : reader.fetchPage(pageId)
            if (!page) {
                println ">>> WARN: page ${pageId} could not be read, skipping it and its children"
                tree.unreadable << pageId
                continue
            }

            tree.pages[pageId] = describe(page, entry)

            def children = reader.fetchChildPages(pageId)
            tree.childrenByParent[pageId] = children.collect { it.id as String }
            children.eachWithIndex { child, index ->
                queue << [id: child.id as String, parentId: pageId, position: index]
            }

            reader.fetchAttachments(pageId).each { attachment ->
                tree.attachments[attachment.id as String] = describe(attachment, pageId)
            }
        }
        return tree
    }

    private Map describe(Map page, Map entry) {
        String filename = converter.sanitizeFilename(page.title as String)
        return [
            title       : page.title,
            parentId    : entry.parentId,
            filename    : filename,
            // The regex is applied to the sanitised name rather than to the raw title, so an
            // author can write it against the underscore form they see in the output.
            adocFilename: stripPagePrefixRegex ? filename.replaceFirst(stripPagePrefixRegex, '') : filename,
            position    : entry.position.toString(),
            status      : 'current',
            contributors: contributorsOf(page)
        ]
    }

    private static Map describe(Map attachment, String pageId) {
        String title = attachment.title as String
        return [
            filename        : title,
            // What Confluence stored, before anything renames it - the storage format refers to
            // the attachment by this name, so a later rename has to keep both.
            originalFilename: title,
            id              : attachment.id as String,
            version         : (attachment.version?.number ?: 1).toString(),
            pageId          : pageId,
            originalId      : '',
            downloadUrl     : attachment._links?.download ?: ''
        ]
    }

    /**
     * @return who wrote the page, creator first, without repeating anyone
     */
    private static List<String> contributorsOf(Map page) {
        def names = []
        def creator = page.history?.createdBy?.displayName
        if (creator) {
            names << creator
        }
        page.history?.contributors?.publishers?.users?.each { user ->
            def name = user?.displayName
            if (name && !names.contains(name)) {
                names << name
            }
        }
        return names
    }
}
