package org.docToolchain.atlassian.confluence.publish

import org.docToolchain.atlassian.confluence.image.EmbeddedDiagram
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States which images become attachments and which stay references.
 *
 * The difference matters beyond markup: an attachment survives the page being read by someone who
 * cannot reach the original host, and a reference does not.
 */
class ImageTransformerSpec extends Specification {

    @TempDir
    Path directory

    /** A one-pixel PNG, as a document embeds one. */
    private static final String DATA_URI = 'data:image/png;base64,' +
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

    /** A diagram, as short as one can be and still be one. */
    private static final String DIAGRAM =
        '<mxfile host="drawio-plugin"><diagram id="view-context" name="Context">x</diagram></mxfile>'

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private ImageTransformer transformer(String baseUrl = 'docs/manual.html') {
        return new ImageTransformer(baseUrl, ['images/.'])
    }

    def 'an image on another host stays a reference to that host'() {
        given:
            def body = bodyOf('<img src="https://example.org/icon.png" width="16">')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads.isEmpty()
            body.html().contains('<ri:url ri:value="https://example.org/icon.png" />')

        and: 'and it keeps the alignment, which a literal once swallowed'
            body.html().contains('ac:align="center"')
    }

    def 'a local image becomes an attachment, referred to by name'() {
        given:
            def body = bodyOf('<img src="images/diagram.png" width="360">')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads*.fileName() == ['diagram.png']
            uploads*.url() == ['docs/images/diagram.png']
            body.html().contains('<ri:attachment ri:filename="diagram.png" />')
            body.html().contains('ac:width="360"')
    }

    def 'a plus in a file name is a plus, not a space'() {
        given: """URLDecoder decodes an HTML form field, where "+" means a space. An image target
                  is a path, where it means itself. A file called "C++ guide.png" was uploaded
                  under the name "C   guide.png" - a name no file on disk has, so the attachment
                  the page then points at is never there."""
            def body = bodyOf('<img src="images/C++%20guide.png">')

        when:
            def uploads = transformer().transformImages(body)

        then: 'the escape is still decoded, and the plus is left alone'
            uploads*.fileName() == ['C++ guide.png']
            uploads*.url() == ['docs/images/C++ guide.png']
            body.html().contains('ri:filename="C++ guide.png"')
    }

    def 'an image without a width gets the one Confluence would use anyway'() {
        given:
            def body = bodyOf('<img src="a.png">')

        when:
            transformer().transformImages(body)

        then:
            body.html().contains('ac:width="500"')
    }

    def 'an embedded image is written to disk with the bytes it carries'() {
        given: 'there is no file to attach, so one has to be made'
            def body = bodyOf("<img src=\"${DATA_URI}\" alt=\"A dot\">")

        when:
            def uploads = new ImageTransformer(
                directory.resolve('manual.html').toString(), ['images/.']).transformImages(body)

        then:
            uploads.size() == 1
            uploads[0].fileName().endsWith('.png')
            body.html().contains('<ri:attachment ri:filename="' + uploads[0].fileName() + '" />')

        and: 'the file holds the image itself, not an empty file of the right name'
            new File(uploads[0].url()).bytes ==
                DATA_URI.substring(DATA_URI.indexOf(',') + 1).decodeBase64()
    }

    def 'two embedded images are one attachment where they are the same image'() {
        given: 'the name comes from the content, so the same content is the same attachment'
            def other = 'data:image/png;base64,' +
                'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIW2P8z8DwHwAFgwJ/lSyBBQAAAABJRU5ErkJggg=='
            def body = bodyOf("<img src=\"${DATA_URI}\" alt=\"one\">" +
                "<img src=\"${DATA_URI}\" alt=\"two\">" +
                "<img src=\"${other}\" alt=\"three\">")

        when:
            def uploads = new ImageTransformer(
                directory.resolve('manual.html').toString(), ['images/.']).transformImages(body)

        then:
            uploads*.fileName()[0] == uploads*.fileName()[1]
            uploads*.fileName()[2] != uploads*.fileName()[0]

        and: 'and each file holds its own image'
            new File(uploads[2].url()).bytes ==
                other.substring(other.indexOf(',') + 1).decodeBase64()
    }

    def 'a quote in a file name does not cut the attachment name short'() {
        given: """A file name is written into an attribute of a fragment that is parsed again.
                  Unescaped, a quote closes the attribute early: the page would then point at
                  "a", while the file uploaded is called 'a\"b.png'."""
            def body = bodyOf('<img src="a%22b.png">')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads[0].fileName() == 'a\"b.png'
            body.selectFirst('ri|attachment').attr('ri:filename') == 'a\"b.png'
    }

    def 'the img element itself is gone afterwards'() {
        given: 'Confluence storage format has no img'
            def body = bodyOf('<img src="a.png"><img src="https://example.org/b.png">')

        when:
            transformer().transformImages(body)

        then:
            body.select('img').isEmpty()
    }

    def 'an escaped name is attached under the name it means'() {
        given:
            def body = bodyOf('<img src="images/my%20diagram.png">')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads[0].fileName() == 'my diagram.png'
    }

    def 'a windows path separator does not become part of the name'() {
        given:
            def body = bodyOf('<img src="a.png">')

        when:
            def uploads = new ImageTransformer('docs\\manual.html', ['images/.'])
                .transformImages(body)

        then:
            uploads[0].url() == 'docs/a.png'
    }

    def 'an exported image is attached under the name Confluence knows it by'() {
        given: '''an export writes the revision it took the file from into the file name, because
                  a document may refer to an older revision of a diagram. Confluence knows the
                  file without that prefix, and the draw.io app looks for exactly that name.'''
            def body = bodyOf('<img src="images/Root/Child/3_diagram.png">')

        when:
            def uploads = transformer().transformImages(body)

        then: 'the file on disk keeps the prefix; the attachment does not'
            uploads*.fileName() == ['diagram.png']
            uploads*.url() == ['docs/images/Root/Child/3_diagram.png']

        and: 'and the page refers to it by that name, or it refers to nothing'
            body.html().contains('<ri:attachment ri:filename="diagram.png" />')

        and: 'the revision travels along, as the only thing that says what we exported from'
            uploads*.exportedVersion() == [3]
    }

    def 'an image an author put there themselves carries no revision'() {
        given:
            def body = bodyOf('<img src="images/diagram.png">')

        when:
            def uploads = transformer().transformImages(body)

        then: 'and nothing is stripped off its name'
            uploads*.fileName() == ['diagram.png']
            uploads*.exportedVersion() == [AttachmentName.NO_VERSION]
    }

    def 'a name the image store chose is not read as an export wrote it'() {
        given: '''The version is in the path the document refers to, and nowhere else. An embedded
                  image found on disk is attached under the name the store found it under - here a
                  file an author called "3_dots.png", which is not revision 3 of anything.'''
            directory.resolve('images').toFile().mkdirs()
            directory.resolve('images/3_dots.png').toFile().bytes =
                DATA_URI.substring(DATA_URI.indexOf(',') + 1).decodeBase64()
            def body = bodyOf("<img src=\"${DATA_URI}\" alt=\"3 dots\">")

        when:
            def uploads = new ImageTransformer(
                directory.resolve('manual.html').toString(), ['images/.']).transformImages(body)

        then:
            uploads*.fileName() == ['3_dots.png']
            uploads*.exportedVersion() == [AttachmentName.NO_VERSION]
    }

    def 'two pictures that differ only in the version keep the names the document uses'() {
        given: '''What the prefix is there for: a page may show an older revision of a diagram
                  beside the current one. Shortened, both would be attached as "diagram.png" and
                  both positions would show whichever was uploaded last.

                  The tags are closed here because this parses as XML, where an unclosed img
                  makes the next one its child; a real document wraps each image in its own div,
                  which closes it.'''
            def body = bodyOf('<img src="images/1_diagram.png"/><img src="images/2_diagram.png"/>')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads*.fileName() == ['1_diagram.png', '2_diagram.png']
            body.html().contains('ri:filename="1_diagram.png"')
            body.html().contains('ri:filename="2_diagram.png"')

        and: '''and neither carries a revision any more: the revision belongs to the attachment of
                the shortened name, and comparing it against a differently named attachment could
                authorise a write over somebody else's file'''
            uploads*.exportedVersion() == [AttachmentName.NO_VERSION, AttachmentName.NO_VERSION]
    }

    def 'screenshots an author numbered keep their numbers'() {
        given: 'the same case, and the more common one'
            def body = bodyOf('<img src="images/01_step.png"/><img src="images/02_step.png"/>')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads*.fileName() == ['01_step.png', '02_step.png']
    }

    def 'a picture used twice on one page is one attachment, under its own name'() {
        given: 'the same file twice is not two files, so there is nothing to keep apart'
            def body = bodyOf('<img src="images/3_diagram.png"/><img src="images/3_diagram.png"/>')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads*.fileName() == ['diagram.png', 'diagram.png']
            uploads*.exportedVersion() == [3, 3]
    }

    def 'a shortened name colliding with a plain one leaves both as they are'() {
        given: 'the plain file has the name the other one would be shortened to'
            def body = bodyOf('<img src="images/diagram.png"/><img src="images/2_diagram.png"/>')

        when:
            def uploads = transformer().transformImages(body)

        then:
            uploads*.fileName() == ['diagram.png', '2_diagram.png']
    }

    def 'two pictures of one name in different folders are named, not merged silently'() {
        given: '''An export writes a folder per page, so two pages may each have an overview.png.
                  Published onto one page they are one attachment either way - this says so
                  instead of showing the wrong picture without a word.'''
            def body = bodyOf('<img src="images/A/1_overview.png"/><img src="images/B/1_overview.png"/>')
            def uploads = []

        when:
            def said = outputOf { uploads = transformer().transformImages(body) }

        then:
            uploads*.fileName() == ['1_overview.png', '1_overview.png']

        and:
            said.contains("more than one file would be attached as 'overview.png'")
            said.contains('images/A/1_overview.png')
            said.contains('images/B/1_overview.png')
            said.contains('whichever is uploaded last')
    }

    private String outputOf(Closure work) {
        def captured = new ByteArrayOutputStream()
        def previous = System.out
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            work()
        } finally {
            System.out = previous
        }
        return captured.toString('UTF-8')
    }

    def 'a document without images asks for no uploads'() {
        expect:
            transformer().transformImages(bodyOf('<p>text</p>')).isEmpty()
    }

    /** A diagram as a draw.io export carries it, written into a picture of the given name. */
    private String carrying(String name, String diagram) {
        directory.resolve('images').toFile().mkdirs()
        def file = directory.resolve('images').resolve(name).toFile()
        file.bytes = EmbeddedDiagram.embedIn(onePixel(), diagram)
        return file.path
    }

    private String plainPng(String name) {
        directory.resolve('images').toFile().mkdirs()
        def file = directory.resolve('images').resolve(name).toFile()
        file.bytes = onePixel()
        return file.path
    }

    /** An SVG carrying a diagram the way the draw.io command line writes one. */
    private String svgCarrying(String name, String diagram) {
        directory.resolve('images').toFile().mkdirs()
        def escaped = diagram.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;')
        def file = directory.resolve('images').resolve(name).toFile()
        file.text = '<svg xmlns="http://www.w3.org/2000/svg" content="' + escaped + '"><g/></svg>'
        return file.path
    }

    private static byte[] onePixel() {
        return DATA_URI.substring(DATA_URI.indexOf(',') + 1).decodeBase64()
    }

    private ImageTransformer inDirectory(boolean writing = true) {
        return new ImageTransformer(directory.resolve('manual.html').toString(), ['images/.'],
            writing)
    }

    def 'a picture carrying a draw.io diagram becomes the editable diagram'() {
        given: '''The file decides, not the document. One image:: line means a picture where the
                  file is a picture and an editable diagram where the file carries one - which is
                  what a draw.io export does by default.'''
            carrying('architecture.png', DIAGRAM)
            def body = bodyOf('<img src="images/architecture.png" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'the macro, not an image'
            body.html().contains('ac:name="drawio"')
            body.html().contains('<ac:parameter ac:name="diagramName">architecture</ac:parameter>')
            body.select('ac|image').isEmpty()

        and: 'the width the document asked of the picture is the width of the diagram'
            body.html().contains('<ac:parameter ac:name="diagramWidth">700</ac:parameter>')

        and: '''and it is as tall as that makes it: the diagram's own size is nowhere in the
                 file, so the preview's shape is the only honest number. One pixel square at 700
                 is 700 tall.'''
            body.html().contains('<ac:parameter ac:name="height">700</ac:parameter>')

        and: '''two attachments: the diagram under the picture's base name with no extension at
                 all, which is what the app binds to, and the picture as its preview'''
            uploads*.fileName() == ['architecture', 'architecture.png']

        and: 'the diagram travels as its own bytes, because it is no file on disk'
            new String(uploads[0].content(), 'UTF-8') == DIAGRAM
            uploads[1].content() == null
    }

    def 'a picture carrying no diagram is left exactly as it was'() {
        given: 'that is the whole rule, and what lets one line mean both things'
            plainPng('flat.png')
            def body = bodyOf('<img src="images/flat.png" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            body.html().contains('<ri:attachment ri:filename="flat.png" />')
            uploads*.fileName() == ['flat.png']
    }

    def 'an SVG carrying a diagram takes the PNG beside it as the preview'() {
        given: '''The app asks for <diagramName>.png by name, so a diagram needs a PNG whatever
                  the picture in the document was. The one beside it under the same name is the
                  only candidate that needs neither a renderer nor a guess.'''
            svgCarrying('core.svg', DIAGRAM)
            plainPng('core.png')
            def body = bodyOf('<img src="images/core.svg" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            body.html().contains('<ac:parameter ac:name="diagramName">core</ac:parameter>')

        and: 'the PNG is attached as the preview, and the SVG is attached at all'
            uploads*.fileName() == ['core', 'core.png']
            uploads[1].url().endsWith('core.png')
    }

    def 'an SVG with no PNG beside it stays a picture, because the preview cannot be invented'() {
        given: '''Without <name>.png the viewer still draws the diagram, but there is no static
                  picture - so an export to PDF has a hole. Rendering one would need draw.io on
                  the machine, which publishing is not allowed to need.'''
            svgCarrying('lonely.svg', DIAGRAM)
            def body = bodyOf('<img src="images/lonely.svg">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads*.fileName() == ['lonely.svg']
    }

    def 'the diagram and the picture each carry the revision they came from'() {
        given: '''DiagramMerge writes <picture>-<diagram>_<name>.png because the two are two
                  attachments and stand at revisions of their own. Handed the diagram's revision,
                  the preview was refused by the clobber guard on the publish right after the
                  export - Confluence held the revision it had, not the diagram's - and a preview
                  somebody else had updated to that number would have passed instead.'''
            carrying('1-2_architecture.png', DIAGRAM)
            def body = bodyOf('<img src="images/1-2_architecture.png">')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'and neither revision is part of the name the app binds to'
            uploads*.fileName() == ['architecture', 'architecture.png']
            uploads*.exportedVersion() == [2, 1]
    }

    def 'one revision in the name is the picture\'s, and says nothing about the diagram'() {
        given: '''Which is what a file an author made themselves looks like. The number means what
                  it means for every other attachment - the revision of the attachment this file
                  is - and the diagram inside it has none we know. Unknown must not authorise a
                  write over what the app wrote.'''
            carrying('3_architecture.png', DIAGRAM)
            def body = bodyOf('<img src="images/3_architecture.png">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            uploads*.fileName() == ['architecture', 'architecture.png']
            uploads*.exportedVersion() == [AttachmentName.NO_VERSION, 3]
    }

    def 'two pictures with one diagram name and two diagrams: the second stays a picture'() {
        given: '''Confluence holds one attachment of a name, so whichever upload went last would
                  decide what both macros show. One of them has to stay what it looks like, and
                  the run has to say which two files are in each other's way.'''
            carrying('same.png', DIAGRAM)
            svgCarrying('same.svg', '<mxfile><diagram id="other">something else</diagram></mxfile>')
            plainPng('same-preview.png')
            // Self-closed, because bodyOf parses as XML, where <img src="x"> stays open
            // and the next one would be read as its child rather than as its sibling.
            def body = bodyOf('<img src="images/same.png" /><img src="images/same.svg" />')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'the first is the diagram'
            uploads*.fileName() == ['same', 'same.png', 'same.svg']

        and: 'and the second is a picture of itself, which is visible rather than silent'
            body.select('ri|attachment')*.attr('ri:filename') == ['same.svg']
    }

    def 'the same diagram shown twice is one attachment and two macros'() {
        given: '''Which is the arrangement Confluence itself writes where one diagram appears in
                  several places on a page: one diagram, one preview, a macro at each spot.'''
            carrying('twice.png', DIAGRAM)
            def body = bodyOf('<img src="images/twice.png" /><img src="images/twice.png" />')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            body.select('ac|structured-macro').size() == 2
            uploads*.fileName() == ['twice', 'twice.png']

        and: 'and the second is the same size as the first, not a macro without one'
            body.select('ac|structured-macro')
                .every { it.html().contains('ac:name="height"') }
    }

    def 'the second macro on one diagram is sized at its own width'() {
        given: '''The document may ask for the same diagram at two sizes. Carrying the first
                  macro's height to the second would squash the picture at the second width.'''
            carrying('twice.png', DIAGRAM)
            def body = bodyOf('<img src="images/twice.png" width="700" />' +
                '<img src="images/twice.png" width="300" />')

        when:
            inDirectory().transformImages(body)

        then: 'one pixel square, so each height is its own width'
            def macros = body.select('ac|structured-macro')*.html()
            macros[0].contains('<ac:parameter ac:name="height">700</ac:parameter>')
            macros[1].contains('<ac:parameter ac:name="height">300</ac:parameter>')
    }

    def 'the names a diagram generates are planned for like any other'() {
        given: '''The page shows 1_arch.svg, whose preview is attached as arch.png, beside an
                  ordinary 2_arch.png, which shortens to the same name. Neither name exists while
                  the collision is being planned, so both passed it and the later upload replaced
                  the earlier: either the picture or the diagram's static preview was then wrong,
                  and nothing said so.'''
            svgCarrying('1_arch.svg', DIAGRAM)
            plainPng('1_arch.png')
            plainPng('2_arch.png')
            def body = bodyOf('<img src="images/1_arch.svg" /><img src="images/2_arch.png" />')
            def uploads = []

        when:
            def said = outputOf { uploads = inDirectory().transformImages(body) }

        then: '''the diagram keeps the name the app binds to - it has to - and the picture keeps
                 the name the document refers to it by'''
            uploads*.fileName() == ['arch', 'arch.png', '2_arch.png']

        and: 'and each name is attached once'
            uploads*.fileName().toSet().size() == uploads.size()

        and: 'said rather than merged silently'
            said.contains("more than one file would be attached as 'arch.png'")
    }

    def 'a dry run names the same attachments and writes nothing'() {
        given: '''The hash a dry run compares is the hash of the body a real run would send, so
                  the body has to be the same one - macro and all.'''
            carrying('architecture.png', DIAGRAM)
            def body = bodyOf('<img src="images/architecture.png">')

        when:
            def uploads = inDirectory(false).transformImages(body)

        then:
            body.html().contains('ac:name="drawio"')
            uploads*.fileName() == ['architecture', 'architecture.png']
    }

    def 'an image the document carries inline is not turned into a diagram'() {
        given: '''A data URI has no name of its own: the store names the file after the hash of
                  its content, so every edit of the diagram would attach it under a new name, leave
                  the one before it behind, and move the macro to a diagram nobody finds twice.'''
            def diagram = EmbeddedDiagram.embedIn(onePixel(), DIAGRAM)
            def body = bodyOf("<img src=\"data:image/png;base64,${diagram.encodeBase64()}\" alt=\"d\">")

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads.size() == 1
    }
}
