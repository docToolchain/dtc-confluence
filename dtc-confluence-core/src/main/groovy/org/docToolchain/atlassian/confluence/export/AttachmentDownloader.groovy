package org.docToolchain.atlassian.confluence.export

/**
 * Writes the attachments of an exported tree to disk, where the converter expects to find them.
 *
 * <p>The path an exported document refers to is
 * {@code images/<folders of the page>/<version>_<filename>}. The version is part of the name
 * because a document may refer to an older revision of a diagram, and the folders mirror the page
 * tree so that two pages may each have an {@code overview.png}.</p>
 *
 * <p>A file that cannot be fetched is reported and skipped. An export of a few hundred pages that
 * stops at one broken attachment is worth less than one that says which files are missing.</p>
 */
class AttachmentDownloader {

    private final ConfluenceReader reader
    private final ConfluenceConverter converter

    AttachmentDownloader(ConfluenceReader reader, ConfluenceConverter converter) {
        this.reader = reader
        this.converter = converter
    }

    /**
     * @param destination the docs directory; files land under its images folder
     * @return the names of the attachments that could not be written
     */
    List<String> downloadAll(ExportedTree tree, File destination) {
        def failed = []
        tree.attachments.each { id, attachment ->
            String name = fileNameOf(tree, attachment)
            if (!attachment.downloadUrl) {
                println ">>> WARN: ${attachment.filename} has no download link, skipping it"
                failed << name
                return
            }
            try {
                byte[] content = reader.download(attachment.downloadUrl as String)
                if (content == null) {
                    println ">>> WARN: ${attachment.filename} is no longer there, skipping it"
                    failed << name
                    return
                }
                File target = new File(new File(destination, 'images'), name)
                target.parentFile?.mkdirs()
                target.bytes = content
            } catch (Exception e) {
                println ">>> WARN: ${attachment.filename} could not be fetched: ${e.message}"
                failed << name
            }
        }
        return failed
    }

    /**
     * @return the path below images/ that the exported document refers to
     */
    private String fileNameOf(ExportedTree tree, Map attachment) {
        def folders = converter.getFolderStructure(tree.pages, attachment.pageId as String)
        // Colons and spaces are legal in Confluence and awkward in a path and in an AsciiDoc
        // image macro, so they go the same way the page file names went.
        String safe = (attachment.filename as String).replaceAll(':', '_').replaceAll(' ', '_')
        String prefix = folders ? folders.join('/') + '/' : ''
        return prefix + attachment.version + '_' + safe
    }
}
