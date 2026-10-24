package org.docToolchain.atlassian.confluence.export

import org.docToolchain.atlassian.confluence.image.EmbeddedDiagram
import org.docToolchain.atlassian.confluence.publish.ImageTransformer
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States how the two or three attachments a draw.io diagram is become one file in the repository.
 *
 * Why it has to happen at all: downloaded as they are, the document refers to the preview and
 * nothing refers to the diagram, so a publish of that document sends a picture back and the
 * diagram is lost. Measured on real pages, the preview the Confluence app keeps beside a diagram
 * carries no source of its own, so there is nothing to fall back on.
 */
class DiagramMergeSpec extends Specification {

    @TempDir
    Path images

    private static final String DIAGRAM =
        '<mxfile host="cwiki"><diagram id="view-core" name="Core">current</diagram></mxfile>'

    /** Measured: sha1('view-core'), which is what the app names that view's preview after. */
    private static final String VIEW_CORE = '62167aa2f85e4935ba9c7c2eba7bb76551487513'

    private static final byte[] ONE_PIXEL = ('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ' +
        'AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==').decodeBase64()

    /** A different picture of the same size, for telling a drawn one from the one on disk. */
    private static final byte[] ANOTHER_PIXEL = ('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ' +
        'AAAADUlEQVQIW2P8z8DwHwAFgwJ/lSyBBQAAAABJRU5ErkJggg==').decodeBase64()

    private ExportedTree tree = new ExportedTree()

    def setup() {
        tree.pages.put('1', [title: 'Root', filename: 'Root', adocFilename: 'Root',
                             parentId: '0', position: '0'])
        tree.bodies.put('1', '<p>a page</p>')
    }

    private Map attachment(String id, String name, Map extra = [:]) {
        def described = [filename: name, originalFilename: name, id: id, version: '1',
                         mediaType: 'image/png', written: '2026-10-23T12:00:00.000Z',
                         pageId: '1', downloadUrl: '/download/' + name] + extra
        tree.attachments.put(id, described)
        return described
    }

    private Path file(String name) {
        def directory = images.resolve('Root')
        directory.toFile().mkdirs()
        return directory.resolve(name)
    }

    private void onDisk(String name, byte[] content) {
        file(name).toFile().bytes = content
    }

    private DiagramMerge merge(DiagramRenderer renderer = null) {
        return new DiagramMerge(images, renderer)
    }

    def 'a diagram and its preview become one picture that carries its own source'() {
        given:
            attachment('mx', 'arch', [mediaType: 'application/vnd.jgraph.mxfile'])
            attachment('png', 'arch.png')
            onDisk('1_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then: 'one picture, and it is the one the document already refers to'
            merged.diagrams() == ['arch']
            EmbeddedDiagram.of(file('1-1_arch.png').toFile().bytes).get() == DIAGRAM

        and: 'the bare diagram is gone from the repository, because it is inside the picture now'
            !file('1_arch').toFile().exists()

        and: 'and gone from the tree, so that nothing downstream offers a file that is not there'
            tree.attachments.keySet() == ['png'] as Set
    }

    def 'the merged picture keeps the preview name, which is what the diagram is named after'() {
        given: '''The Groovy original wrote <base>.drawio.png. The publish side derives the
                  diagram's name from the file name, and x.drawio is not x: renamed, a republish
                  would attach a second diagram beside the one it came from and orphan the
                  first.'''
            attachment('mx', 'arch', [mediaType: 'application/vnd.jgraph.mxfile'])
            attachment('png', 'arch.png')
            onDisk('1_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            merge().merge(tree)

        then:
            file('1-1_arch.png').toFile().exists()
            !file('1-1_arch.drawio.png').toFile().exists()
    }

    def 'the source is read from the diagram, never from the preview'() {
        given: '''A preview is derived, and a derived file may be older than what it derives from.
                  A preview uploaded from a draw.io export carries the state it was rendered at,
                  and reading that would write a stale diagram into the repository as if it were
                  current.'''
            def older = '<mxfile><diagram id="view-core">an earlier state</diagram></mxfile>'
            attachment('mx', 'arch', [mediaType: 'application/vnd.jgraph.mxfile'])
            attachment('png', 'arch.png')
            onDisk('1_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', EmbeddedDiagram.embedIn(ONE_PIXEL, older))

        when:
            merge().merge(tree)

        then:
            EmbeddedDiagram.of(file('1-1_arch.png').toFile().bytes).get() == DIAGRAM
    }

    def 'the merged picture carries both revisions, its own and the diagram\'s'() {
        given: '''The revision in the file name is the only thing that tells the publish side
                  whether somebody has written an attachment since the export, and here one file
                  goes back as two attachments that can stand at two revisions. The picture's
                  comes first, as it does for every other file; the diagram's is what guards the
                  attachment whose loss matters, a human's edit.'''
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            merge().merge(tree)

        then:
            file('1-2_arch.png').toFile().exists()
            !file('1_arch.png').toFile().exists()
            !file('2_arch.png').toFile().exists()

        and: 'and the document is told to refer to that name'
            tree.attachments['png'].version == '1-2'
    }

    def 'a round trip keeps the two revisions apart, which is what guards each of them'() {
        given: '''The export and the publish, against one file: the diagram a revision ahead of
                  its preview, which is what a page looks like where the app drew and a human then
                  edited. The publisher reads both back out of the one name it is given.'''
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            merge().merge(tree)
            def written = tree.attachments['png'].version + '_arch.png'
            def body = Jsoup.parse('<div><img src="' + written + '"/></div>', '',
                Parser.xmlParser()).selectFirst('div')
            def uploads = new ImageTransformer(file('page.html').toString(), [])
                .transformImages(body)

        then: 'the diagram is guarded by the diagram\'s revision and the preview by its own'
            uploads*.fileName() == ['arch', 'arch.png']
            uploads*.exportedVersion() == [2, 1]

        and: 'and the diagram that goes back is the one the export read out of the mxfile'
            new String(uploads[0].content(), 'UTF-8') == DIAGRAM
    }

    def 'a preview older than its diagram is reported, naming the view'() {
        given: '''Measured on a real page: after a human edited two pinned views, the diagram and
                  the default preview went to revision 2 while both pinned previews stayed at 1.
                  Confluence's export view carries no macro at all, only those images, so a PDF of
                  a pinned view shows the state of the last upload.'''
            tree.bodies.put('1', '<ac:structured-macro ac:name="drawio">' +
                '<ac:parameter ac:name="diagramName">arch</ac:parameter>' +
                '<ac:parameter ac:name="aspect">view-core</ac:parameter></ac:structured-macro>')
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '2', written: '2026-10-23T12:48:20.000Z'])
            attachment('pinned', 'arch-' + VIEW_CORE + '.png',
                [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('2_arch.png', ONE_PIXEL)
            onDisk('1_arch-' + VIEW_CORE + '.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then: 'named as the page names it, not by the hash the file name carries'
            merged.problems().size() == 1
            merged.problems()[0].contains("the view 'view-core' of 'arch'")
            merged.problems()[0].contains('older than its diagram')

        and: 'the pinned view stays a picture: named <diagram>-<hash>, it would be a new diagram'
            tree.attachments.keySet() == ['png', 'pinned'] as Set
            EmbeddedDiagram.of(file('1_arch-' + VIEW_CORE + '.png').toFile().bytes).isEmpty()
    }

    def 'a stale preview is drawn again where a renderer happens to be on the machine'() {
        given:
            def renderer = Mock(DiagramRenderer)
            // A picture that is not the one on disk, so that using the old one is visible.
            def fresh = ANOTHER_PIXEL
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            def merged = merge(renderer).merge(tree)

        then: 'the diagram it was asked to draw is the master, and the page is the default one'
            1 * renderer.render(DIAGRAM, '') >> Optional.of(fresh)

        and: 'what it drew is what the repository holds, with the diagram inside it'
            merged.problems()[0].contains('drawn again from the diagram by')
            file('1-2_arch.png').toFile().bytes == EmbeddedDiagram.embedIn(ANOTHER_PIXEL, DIAGRAM)
            file('1-2_arch.png').toFile().bytes != EmbeddedDiagram.embedIn(ONE_PIXEL, DIAGRAM)
    }

    def 'without a renderer the picture is exported as it is, with the current diagram in it'() {
        given: '''The degradation is one old picture inside a file whose source is right, and the
                  message says so. Requiring draw.io to export would be worse: nobody could
                  export from a machine that has only Java.'''
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then:
            merged.problems()[0].contains('exported as it is, with the current diagram inside it')
            merged.diagrams() == ['arch']
            EmbeddedDiagram.of(file('1-2_arch.png').toFile().bytes).get() == DIAGRAM
    }

    def 'a preview the diagram cannot go into keeps the diagram on disk'() {
        given: '''A picture truncated after its header is still a PNG by its signature, and
                  nothing can be written into it. Reported as merged, the bare mxfile would be
                  deleted and the only editable copy of a human's work gone - with the run
                  saying it had succeeded.'''
            attachment('mx', 'arch', [mediaType: 'application/vnd.jgraph.mxfile'])
            attachment('png', 'arch.png')
            onDisk('1_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL[0..<16] as byte[])

        when:
            def merged = merge().merge(tree)

        then:
            merged.diagrams().isEmpty()
            merged.problems()[0].contains("the diagram 'arch' could not be put into its preview")

        and: 'both files are still there, and the tree still offers the diagram'
            file('1_arch').toFile().exists()
            tree.attachments.keySet() == ['mx', 'png'] as Set
    }

    def 'a diagram with no preview attached is reported rather than merged into nothing'() {
        given: 'the app asks for <name>.png by name; there is no other picture to put it in'
            attachment('mx', 'arch', [mediaType: 'application/vnd.jgraph.mxfile'])
            onDisk('1_arch', DIAGRAM.bytes)

        when:
            def merged = merge().merge(tree)

        then:
            merged.diagrams().isEmpty()
            merged.problems()[0].contains('has no arch.png attached')
            tree.attachments.keySet() == ['mx'] as Set
    }

    def 'a diagram this publisher attached is recognised although its media type is not the app'() {
        given: '''Every upload of ours goes out as application/octet-stream - measured, and the
                  app binds by name anyway - so after one round trip the media type says nothing.
                  What is left is that a diagram's name carries no extension, and that the file
                  says what it is.'''
            attachment('mx', 'arch', [mediaType: 'application/octet-stream'])
            attachment('png', 'arch.png')
            onDisk('1_arch', DIAGRAM.bytes)
            onDisk('1_arch.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then:
            merged.diagrams() == ['arch']
    }

    def 'a diagram whose own name carries a dot is still a diagram'() {
        given: '''Publishing architecture.context.png attaches the source under the picture's base
                  name, architecture.context - a name with a dot in it - and every upload of ours
                  goes out as application/octet-stream. Neither signal matched, so the export
                  skipped the authoritative source and left whatever older one was in the
                  preview.'''
            attachment('mx', 'architecture.context', [mediaType: 'application/octet-stream'])
            attachment('png', 'architecture.context.png')
            onDisk('1_architecture.context', DIAGRAM.bytes)
            def older = '<mxfile><diagram id="x">an earlier state</diagram></mxfile>'
            onDisk('1_architecture.context.png', EmbeddedDiagram.embedIn(ONE_PIXEL, older))

        when:
            def merged = merge().merge(tree)

        then: 'the mxfile decides, as it does for every other diagram'
            merged.diagrams() == ['architecture.context']
            EmbeddedDiagram.of(file('1-1_architecture.context.png').toFile().bytes).get() == DIAGRAM
    }

    def 'an attachment that only looks like one is left alone'() {
        given: 'a file with no extension is a candidate; what decides is what is in it'
            attachment('other', 'LICENSE', [mediaType: 'text/plain'])
            attachment('png', 'LICENSE.png')
            onDisk('1_LICENSE', 'Apache License, Version 2.0'.bytes)
            onDisk('1_LICENSE.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then:
            merged.diagrams().isEmpty()
            merged.problems().isEmpty()
            tree.attachments.keySet() == ['other', 'png'] as Set
            file('1_LICENSE').toFile().exists()
    }

    def 'a page without a diagram is a merge that does nothing and says nothing'() {
        given:
            attachment('png', 'photo.png')
            onDisk('1_photo.png', ONE_PIXEL)

        when:
            def merged = merge().merge(tree)

        then:
            merged.diagrams().isEmpty()
            merged.problems().isEmpty()
            EmbeddedDiagram.of(file('1_photo.png').toFile().bytes).isEmpty()
    }

    def 'a stale pinned view is drawn as that view, not as the first page of the file'() {
        given: '''Measured against the real draw.io on the PATH: handed the hash out of the file
                  name, it found no page of that id and drew page one - so both pinned views came
                  back the same picture, 76 142 bytes each, and neither was the view it replaced.
                  What draws a view is the view's own id, which only the page's macro carries.'''
            def renderer = Mock(DiagramRenderer)
            tree.bodies.put('1', '<ac:structured-macro ac:name="drawio">' +
                '<ac:parameter ac:name="diagramName">arch</ac:parameter>' +
                '<ac:parameter ac:name="aspect">view-core</ac:parameter></ac:structured-macro>')
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '2', written: '2026-10-23T12:48:20.000Z'])
            attachment('pinned', 'arch-' + VIEW_CORE + '.png',
                [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('2_arch.png', ONE_PIXEL)
            onDisk('1_arch-' + VIEW_CORE + '.png', ONE_PIXEL)

        when:
            merge(renderer).merge(tree)

        then: 'the view, not the hash of it that names the file'
            1 * renderer.render(DIAGRAM, 'view-core') >> Optional.of(ANOTHER_PIXEL)
            0 * renderer.render(_, VIEW_CORE)

        and: 'and what it drew is the picture in the repository'
            file('1_arch-' + VIEW_CORE + '.png').toFile().bytes == ANOTHER_PIXEL
    }

    def 'a pinned view the page no longer names is reported and not drawn at all'() {
        given: '''A hash cannot be undone, so a preview whose aspect is nowhere on the page names
                  a view nothing can draw. Drawing the first page instead would put the wrong view
                  where that one was, which is worse than an old picture of the right one.'''
            def renderer = Mock(DiagramRenderer)
            attachment('mx', 'arch', [version: '2', mediaType: 'application/vnd.jgraph.mxfile',
                                      written: '2026-10-23T12:48:20.000Z'])
            attachment('png', 'arch.png', [version: '2', written: '2026-10-23T12:48:20.000Z'])
            attachment('pinned', 'arch-' + VIEW_CORE + '.png',
                [version: '1', written: '2026-10-23T12:46:35.000Z'])
            onDisk('2_arch', DIAGRAM.bytes)
            onDisk('2_arch.png', ONE_PIXEL)
            onDisk('1_arch-' + VIEW_CORE + '.png', ONE_PIXEL)

        when:
            def merged = merge(renderer).merge(tree)

        then: 'nothing is drawn, and it says which preview and that it is old'
            0 * renderer.render(_, _)
            merged.problems().find { it.contains('older than its diagram') }
                ?.contains("the pinned view of 'arch' behind " + VIEW_CORE)
    }
}
