package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States where an attachment lands, and what happens when one cannot be fetched.
 */
class AttachmentDownloaderSpec extends Specification {

    @TempDir
    Path destination

    private ConfluenceReader reader = Mock(ConfluenceReader)
    private AttachmentDownloader downloader = new AttachmentDownloader(reader)

    private static ExportedTree treeWith(Map... attachments) {
        def tree = new ExportedTree()
        tree.pages.putAll(
            '1': [title: 'Root', parentId: '0', filename: 'Root', adocFilename: 'Root'],
            '2': [title: 'Child', parentId: '1', filename: 'Child', adocFilename: 'Child'])
        attachments.eachWithIndex { attachment, index ->
            tree.attachments["att${index}" as String] = attachment
        }
        return tree
    }

    private File imageAt(String path) { new File(destination.toFile(), "images/${path}") }

    def 'an attachment lands under the folders of its page, with its version in the name'() {
        given: 'a document may refer to an older revision, so the version is part of the name'
            def tree = treeWith([filename: 'diagram.png', version: '3', pageId: '2',
                                 downloadUrl: '/download/attachments/2/diagram.png'])
            reader.download(_) >> 'PNGDATA'.bytes

        when:
            def failed = downloader.downloadAll(tree, destination.toFile())

        then:
            failed.isEmpty()
            imageAt('Root/3_diagram.png').exists()
            imageAt('Root/3_diagram.png').bytes == 'PNGDATA'.bytes
    }

    def 'the bytes are written as they came, not as text'() {
        given: 'a PNG is not UTF-8, and reading it as such would corrupt it'
            byte[] binary = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF] as byte[]
            def tree = treeWith([filename: 'a.png', version: '1', pageId: '1',
                                 downloadUrl: '/download/a.png'])
            reader.download(_) >> binary

        when:
            downloader.downloadAll(tree, destination.toFile())

        then:
            imageAt('1_a.png').bytes == binary
    }

    def 'an awkward file name becomes one a path accepts'() {
        given:
            def tree = treeWith([filename: 'my diagram: final.png', version: '2', pageId: '1',
                                 downloadUrl: '/download/x'])
            reader.download(_) >> 'x'.bytes

        when:
            downloader.downloadAll(tree, destination.toFile())

        then:
            imageAt('2_my_diagram__final.png').exists()
    }

    def 'a name that would reach out of the image directory stays in it'() {
        given: '''the name comes from the server: an export must not be able to write a file
                  anywhere on the machine that runs it'''
            def tree = treeWith([filename: name, version: '1', pageId: '1',
                                 downloadUrl: '/download/x'])
            reader.download(_) >> 'x'.bytes

        when:
            downloader.downloadAll(tree, destination.toFile())

        then: 'the separators are gone, so the file is one name below images/'
            imageAt(landsAt).exists()
            !new File(destination.toFile().parentFile, 'escaped.png').exists()
            !new File(destination.toFile(), 'escaped.png').exists()

        where:
            name                          || landsAt
            '../../escaped.png'           || '1_.._.._escaped.png'
            '..\\..\\escaped.png'         || '1_.._.._escaped.png'
            '/etc/escaped.png'            || '1__etc_escaped.png'
    }

    def 'an attachment without a download link is reported, and the rest still lands'() {
        given:
            def tree = treeWith(
                [filename: 'broken.png', version: '1', pageId: '1', downloadUrl: ''],
                [filename: 'fine.png', version: '1', pageId: '1', downloadUrl: '/download/fine'])
            reader.download('/download/fine') >> 'ok'.bytes

        when:
            def failed = downloader.downloadAll(tree, destination.toFile())

        then: 'an export that stops at one broken file is worth less than one that names it'
            failed == ['1_broken.png']
            imageAt('1_fine.png').exists()
    }

    def 'an attachment that is gone is reported rather than written empty'() {
        given:
            def tree = treeWith([filename: 'gone.png', version: '1', pageId: '1',
                                 downloadUrl: '/download/gone'])
            reader.download(_) >> null

        when:
            def failed = downloader.downloadAll(tree, destination.toFile())

        then:
            failed == ['1_gone.png']
            !imageAt('1_gone.png').exists()
    }

    def 'a download that fails outright is reported, and the rest still lands'() {
        given:
            def tree = treeWith(
                [filename: 'boom.png', version: '1', pageId: '1', downloadUrl: '/download/boom'],
                [filename: 'fine.png', version: '1', pageId: '1', downloadUrl: '/download/fine'])
            reader.download('/download/boom') >> { throw new RuntimeException('connection reset') }
            reader.download('/download/fine') >> 'ok'.bytes

        when:
            def failed = downloader.downloadAll(tree, destination.toFile())

        then:
            failed == ['1_boom.png']
            imageAt('1_fine.png').exists()
    }

    def 'nothing to download is not a failure'() {
        expect:
            downloader.downloadAll(new ExportedTree(), destination.toFile()).isEmpty()
    }
}
