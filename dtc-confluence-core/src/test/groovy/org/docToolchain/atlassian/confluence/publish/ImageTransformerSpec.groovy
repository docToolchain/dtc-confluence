package org.docToolchain.atlassian.confluence.publish

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

    def 'a document without images asks for no uploads'() {
        expect:
            transformer().transformImages(bodyOf('<p>text</p>')).isEmpty()
    }
}
