package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.atlassian.confluence.clients.RemoteAttachment
import org.docToolchain.util.ContentHash
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/**
 * States the name an attachment is published under, and when a publish refuses to write one.
 *
 * An export writes a diagram as "<version>_<name>", because a document may refer to an older
 * revision of it. That prefix is provenance, not a name: published under it, the draw.io app finds
 * nothing, because it binds a diagram by exact attachment name. So the name goes back as it was,
 * and the version it came from becomes the answer to a different question - has somebody else
 * written this since?
 */
class PublishAttachmentSpec extends Specification {

    @TempDir
    Path documents

    /** The first four bytes of a PNG: enough to be a file, little enough to read in a failure. */
    private static final byte[] DIAGRAM = [0x89, 0x50, 0x4E, 0x47] as byte[]

    private ConfluenceClient client = Mock(ConfluenceClient)

    /** One file per image of a page, so two images of one page are not the same content. */
    private static byte[] diagram(int index) {
        return (DIAGRAM.toList() + [index as byte]) as byte[]
    }

    /** What the publisher writes into the comment of what it uploads. */
    private static String localHash(int index = 0) {
        return ContentHash.md5(new String(diagram(index), Charset.defaultCharset()))
    }

    /** The HTML AsciiDoctor produces from an exported document: the path carries the version. */
    private static String documentReferring(List<String> sources) {
        String blocks = sources.collect {
            "<div class=\"imageblock\"><div class=\"content\"><img src=\"${it}\" alt=\"Diagram\"></div></div>"
        }.join('\n')
        return """<!DOCTYPE html>
<html lang=""><head><meta charset="UTF-8"><title>Diagrams</title></head><body class="article">
<div id="header"><h1>Diagrams</h1></div>
<div id="content"><div class="sect1"><h2 id="_s">S</h2><div class="sectionbody">
${blocks}
</div></div></div></body></html>"""
    }

    /** The body the publisher sent, so that what the page refers to can be read back. */
    private String published = ''

    private Asciidoc2ConfluenceTask taskFor(String source, Object foreignAttachments = null) {
        return taskForAll([source], foreignAttachments)
    }

    private Asciidoc2ConfluenceTask taskForAll(List<String> sources,
                                               Object foreignAttachments = null) {
        sources.eachWithIndex { source, index ->
            Path image = documents.resolve(source)
            Files.createDirectories(image.parent)
            Files.write(image, diagram(index))
        }
        new File(documents.toFile(), 'diagrams.html').text = documentReferring(sources)

        def config = new ConfigObject()
        config.docDir = documents.toString()
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 0
        config.confluence.input = [[file: 'diagrams.html', ancestorId: '99']]
        if (foreignAttachments != null) {
            config.confluence.foreignAttachments = foreignAttachments
        }

        def task = Asciidoc2ConfluenceTask.From(config, documents.toString())
        client.fetchPagesBySpaceKey(_, _) >> [:]
        client.fetchPagesByAncestorId(_, _) >> [:]
        client.retrievePageIdByName(_, _) >> null
        client.retrieveFullPageById(_) >> [:]
        client.createPage(_, _, _, _, _) >> { String title, String space, Object body,
                                              String comment, String parent ->
            published = body as String
            return [id: '1000']
        }
        task.confluenceClient = client
        return task
    }

    /** What Confluence answers for an attachment it holds, read into what the publisher reads. */
    private void holding(RemoteAttachment remote) {
        client.getAttachment(_, _) >> [results: [[id: remote.id()]]]
        client.attachmentOf(_) >> remote
    }

    def 'an exported attachment is published under the name Confluence knows it by'() {
        given: 'nothing of that name is there yet'
            def task = taskFor('images/Root/Child/3_diagram.png')

        when:
            task.execute()

        then: 'the version prefix is provenance and stays on disk, where the export wrote it'
            1 * client.createAttachment('1000', _, 'diagram.png', _, _)
            0 * client.createAttachment('1000', _, '3_diagram.png', _, _)

        and: 'and the page refers to the same name, or it would refer to nothing'
            published.contains('ri:filename="diagram.png"')
            !published.contains('3_diagram.png')
    }

    def 'a diagram without a file extension keeps its name too'() {
        given: '''a draw.io diagram is attached under a name with no extension at all, with its
                  preview beside it as "<name>.png" - renaming either one breaks the macro'''
            def task = taskFor('images/Root/12_dummy-diagram')

        when:
            task.execute()

        then:
            1 * client.createAttachment('1000', _, 'dummy-diagram', _, _)
    }

    def 'a publish refuses to write over an attachment whose version has moved on'() {
        given: '''measured on a real instance: the draw.io app writes its own version comment,
                  which carries no hash of ours, so no hash comparison can recognise it'''
            def task = taskFor('images/Root/Child/3_diagram.png')
            holding(new RemoteAttachment('att1', 4, 'draw.io diagram'))

        when:
            task.execute()

        then: 'the run says what it found rather than discarding somebody else edit'
            def refused = thrown(IllegalStateException)
            refused.message.contains("'diagram.png'")
            refused.message.contains('version 4')
            refused.message.contains('exported from version 3')
            refused.message.contains('draw.io diagram')

        and:
            0 * client.updateAttachment(_, _, _, _, _, _)
    }

    def 'the revision the export came from is this run to write'() {
        given: 'nobody has touched it since the export, so there is nothing to lose'
            def task = taskFor('images/Root/Child/3_diagram.png')
            holding(new RemoteAttachment('att1', 3, 'draw.io diagram'))

        when:
            task.execute()

        then:
            1 * client.updateAttachment('1000', 'att1', _, 'diagram.png', _, localHash())
    }

    def 'an attachment this publisher wrote is written again'() {
        given: '''the hash in the comment is what says who wrote the revision Confluence holds;
                  this one has the shape only an upload of ours writes - an MD5 between hash
                  marks - and says the content was different when we wrote it'''
            def task = taskFor('images/Root/Child/3_diagram.png')
            holding(new RemoteAttachment('att1', 9,
                'automatically uploaded\r\n#0123456789abcdef0123456789abcdef#'))

        when:
            task.execute()

        then: 'nine revisions on from the export, and still ours'
            1 * client.updateAttachment('1000', 'att1', _, 'diagram.png', _, localHash())
    }

    def 'an attachment that already holds what would be uploaded is left alone'() {
        given: 'an upload would bump the version and notify every watcher for nothing'
            def task = taskFor('images/Root/Child/3_diagram.png')
            holding(new RemoteAttachment('att1', 9, "automatically uploaded\r\n#${localHash()}#"))

        when:
            task.execute()

        then:
            0 * client.updateAttachment(_, _, _, _, _, _)
            0 * client.createAttachment(_, _, _, _, _)
    }

    def 'an attachment nobody exported and nobody here wrote is left alone'() {
        given: '''an attachment created in Confluence has no version to compare against, and
                  unknown means not ours - never the other way round'''
            def task = taskFor('images/diagram.png')
            holding(new RemoteAttachment('att1', 1, 'added by hand'))

        when:
            task.execute()

        then:
            def refused = thrown(IllegalStateException)
            refused.message.contains('carries no version')
            0 * client.updateAttachment(_, _, _, _, _, _)
    }

    def 'skip leaves a foreign attachment and carries on'() {
        given:
            def task = taskFor('images/Root/Child/3_diagram.png', 'skip')
            holding(new RemoteAttachment('att1', 4, 'draw.io diagram'))

        when:
            task.execute()

        then: 'the page is published, the attachment is not'
            noExceptionThrown()
            0 * client.updateAttachment(_, _, _, _, _, _)
    }

    def 'two pictures of one page are two attachments, under the names the document uses'() {
        given: '''Shortening both names would attach one file twice: the page would show one
                  picture in both places, and the first upload's hash would authorise the
                  second.'''
            def task = taskForAll(['images/Root/1_diagram.png', 'images/Root/2_diagram.png'])

        when:
            task.execute()

        then:
            1 * client.createAttachment('1000', _, '1_diagram.png', _, localHash(0))
            1 * client.createAttachment('1000', _, '2_diagram.png', _, localHash(1))
            0 * client.createAttachment('1000', _, 'diagram.png', _, _)

        and: 'and the page refers to each of them by the name it was attached under'
            published.contains('ri:filename="1_diagram.png"')
            published.contains('ri:filename="2_diagram.png"')
    }

    def 'a comment that merely has hash marks in it is not a comment of ours'() {
        given: '''Carrying a hash is what says this publisher wrote the revision Confluence
                  holds. Any hash-delimited text used to count, so a sentence such as this one
                  authorised a write over somebody else's revision.'''
            def task = taskFor('images/Root/Child/3_diagram.png')
            holding(new RemoteAttachment('att1', 4, 'Manually revised; see #123#'))

        when:
            task.execute()

        then:
            thrown(IllegalStateException)
            0 * client.updateAttachment(_, _, _, _, _, _)
    }

    def 'overwrite writes it anyway, which is what every run did before the guard'() {
        given:
            def task = taskFor('images/Root/Child/3_diagram.png', 'overwrite')
            holding(new RemoteAttachment('att1', 4, 'draw.io diagram'))

        when:
            task.execute()

        then:
            1 * client.updateAttachment('1000', 'att1', _, 'diagram.png', _, localHash())
    }
}
