package org.docToolchain.atlassian.confluence.publish

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States how several views of one draw.io file reach one Confluence page.
 *
 * The other arm reads a diagram out of a picture that carries it. This one is for pictures that
 * carry nothing: one model, several views, a flat picture per view, and the names are what say
 * which view is which. The page gets one attachment and a macro per view, each pinned to its own.
 */
class SharedDiagramViewSpec extends Specification {

    @TempDir
    Path directory

    /** One model, two views, as a generator writes them into a single file. */
    private static final String SHARED = '<mxfile host="bausteinsicht">' +
        '<diagram id="view-context" name="System Context">a</diagram>' +
        '<diagram id="view-core" name="Core Packages">b</diagram>' +
        '</mxfile>'

    private static final String DATA_URI = 'data:image/png;base64,' +
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    /** Counted on the markup, because a namespaced selector matches none of this. */
    private static int macrosIn(org.jsoup.nodes.Element body) {
        return body.html().count('ac:name="drawio"')
    }

    private ImageTransformer inDirectory() {
        return new ImageTransformer(directory.resolve('manual.html').toString(), ['images/.'])
    }

    private void sharedDiagram(String name = 'architecture.drawio', String content = SHARED) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().text = content
    }

    /** A picture of a view: flat, carrying nothing, named for the view it shows. */
    private void view(String name) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().text =
            '<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>'
    }

    /** The PNG beside it, which is where the macro's height comes from. */
    private void shapeFor(String name) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().bytes =
            DATA_URI.substring(DATA_URI.indexOf(',') + 1).decodeBase64()
    }

    def 'a picture that names a view becomes a macro pinned to it'() {
        given: '''The picture carries no diagram at all - draw.io can embed only a whole model,
                  never a pointer into one - so the name is what says which view this is.'''
            sharedDiagram()
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'the macro, not an image'
            body.html().contains('ac:name="drawio"')
            body.select('ac|image').isEmpty()

        and: 'bound to the shared diagram, opening on this view'
            body.html().contains('<ac:parameter ac:name="diagramName">architecture</ac:parameter>')
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')

        and: '''with the diagram's own cross-view navigation disabled: those boxes are real links,
                and one click would turn this spot into another view and leave it there'''
            body.html().contains('<ac:parameter ac:name="links">none</ac:parameter>')

        and: 'one attachment, the diagram itself, under its name with no extension'
            uploads*.fileName() == ['architecture']
            uploads[0].url().endsWith('images/architecture.drawio')

        and: 'read from the file rather than carried, because here it is a file on disk'
            uploads[0].content() == null
    }

    def 'several views of one diagram are one attachment and a macro each'() {
        given: 'which is the point: one model, one editable file, several places on one page'
            sharedDiagram()
            view('architecture-context.svg')
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-context.svg" width="700"/>' +
                '<img src="images/architecture-core.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'attached once'
            uploads*.fileName() == ['architecture']

        and: 'and each spot pinned to its own view'
            body.html().contains('<ac:parameter ac:name="aspect">view-context</ac:parameter>')
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')
            macrosIn(body) == 2
    }

    def 'the pictures themselves are never attached'() {
        given: '''They are for the targets that are not Confluence. The app draws every view live
                  from the diagram, and Confluence will not render an uploaded SVG inline
                  anyway.'''
            sharedDiagram()
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        expect:
            inDirectory().transformImages(body)*.fileName() == ['architecture']
    }

    def 'the diagram goes up as an attachment, not as a link to one'() {
        given: '''How an extension-less attachment used to be possible at all: a link to a local
                  file, which the publisher turns into a view-file macro and a link beside it.
                  Here nothing links to the diagram - the convention names it and it is attached
                  outright - so neither appears on the page.'''
            sharedDiagram()
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        when:
            inDirectory().transformImages(body)

        then:
            !body.html().contains('view-file')
            body.select('ac|link').isEmpty()
    }

    def 'the height is read off the picture beside it, where there is one'() {
        given: 'one pixel square, asked for at 700, is 700 tall'
            sharedDiagram()
            view('architecture-core.svg')
            shapeFor('architecture-core.png')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        when:
            inDirectory().transformImages(body)

        then:
            body.html().contains('<ac:parameter ac:name="height">700</ac:parameter>')
    }

    def 'with no picture beside it the macro carries no height at all'() {
        given: 'better no parameter than a number nobody measured'
            sharedDiagram()
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        when:
            inDirectory().transformImages(body)

        then:
            !body.html().contains('ac:name="height"')
    }

    def 'a picture following no convention is left exactly as it was'() {
        given: sharedDiagram()
            view('architecture.svg')
            def body = bodyOf('<img src="images/architecture.svg" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads*.fileName() == ['architecture.svg']
    }

    def 'a name that follows it with no diagram beside it stays a picture'() {
        given: '''And says nothing. One underscore too many in a file name is far likelier than a
                  diagram somebody forgot to commit, and a warning on every such name would bury
                  the ones that matter.'''
            view('architecture-core.svg')
            def body = bodyOf('<img src="images/architecture-core.svg" width="700">')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads*.fileName() == ['architecture-core.svg']
    }

    def 'two different diagrams claiming one name leave the second a picture'() {
        given: '''Confluence holds one attachment of a name. Whichever went up last would decide
                  what both spots show, so the second stays a picture and the run says why.'''
            sharedDiagram()
            directory.resolve('other').toFile().mkdirs()
            directory.resolve('other').resolve('architecture.drawio').toFile().text =
                '<mxfile><diagram id="view-context" name="Other">different</diagram></mxfile>'
            view('architecture-core.svg')
            directory.resolve('other').resolve('architecture-context.svg').toFile().text =
                '<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>'
            def body = bodyOf('<img src="images/architecture-core.svg" width="700"/>' +
                '<img src="other/architecture-context.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then: 'one diagram, one macro, and the other left a picture'
            uploads*.fileName() == ['architecture', 'architecture-context.svg']
            macrosIn(body) == 1
            body.html().count('<ac:image') == 1
    }

    /** Two wide and one tall. */
    private void wide(String name) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().bytes =
            'iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAADUlEQVR4nGP4z8AARAAI/gH/xp559wAAAABJRU5ErkJggg=='.decodeBase64()
    }

    /** One wide and two tall. */
    private void tall(String name) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().bytes =
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAACCAIAAAAW4yFwAAAADklEQVR4nGP4z8DAAMQACf4B/4PiLjgAAAAASUVORK5CYII='.decodeBase64()
    }

    def 'each view is as tall as its own picture, not as the first one'() {
        given: '''The views of one model are not the same shape. Measured on the three the example
                  publishes: 944x694, 844x974 and 844x794. Sized off the diagram they share, the
                  first view's shape would have squashed the other two by a third.'''
            sharedDiagram()
            view('architecture-context.svg')
            view('architecture-core.svg')
            wide('architecture-context.png')
            tall('architecture-core.png')
            def body = bodyOf('<img src="images/architecture-context.svg" width="100"/>' +
                '<img src="images/architecture-core.svg" width="100"/>')

        when:
            inDirectory().transformImages(body)

        then: 'two pixels wide and one tall is 50 at a width of 100; one by two is 200'
            body.html().contains('<ac:parameter ac:name="height">50</ac:parameter>')
            body.html().contains('<ac:parameter ac:name="height">200</ac:parameter>')
    }

    /** A picture that carries a diagram, the way a draw.io export does. */
    private void carrying(String name, String diagram) {
        directory.resolve('images').toFile().mkdirs()
        directory.resolve('images').resolve(name).toFile().bytes =
            org.docToolchain.atlassian.confluence.image.EmbeddedDiagram.embedIn(
                DATA_URI.substring(DATA_URI.indexOf(',') + 1).decodeBase64(), diagram)
    }

    def 'a picture that fits both arms is published as the one it carries, and said so'() {
        given: '''The file decides, which is the rule everywhere else here. But the two
                  arrangements look the same in the document and are not the same page: draw.io
                  embeds the WHOLE model into every picture it exports, so three views published
                  this way are three attachments each holding all three.'''
            sharedDiagram()
            carrying('architecture-core.png', SHARED)
            def body = bodyOf('<img src="images/architecture-core.png" width="700"/>')
            def said = new ByteArrayOutputStream()
            def wasOut = System.out
            System.out = new PrintStream(said, true, 'UTF-8')

        when:
            def uploads = inDirectory().transformImages(body)
            System.out = wasOut

        then: 'the diagram it carries wins, under its own full name'
            uploads*.fileName() == ['architecture-core', 'architecture-core.png']
            !body.html().contains('ac:name="aspect"')

        and: 'and the run says which arrangement it did not take, and how to get it'
            said.toString('UTF-8').contains('WARN')
            said.toString('UTF-8').contains('architecture.drawio')
            said.toString('UTF-8').contains('without the embedded model')
    }

    def 'a picture carrying a diagram with no shared file beside it says nothing'() {
        given: 'the ordinary single-view case, which must stay quiet'
            carrying('architecture-core.png', SHARED)
            def body = bodyOf('<img src="images/architecture-core.png" width="700"/>')
            def said = new ByteArrayOutputStream()
            def wasOut = System.out
            System.out = new PrintStream(said, true, 'UTF-8')

        when:
            inDirectory().transformImages(body)
            System.out = wasOut

        then:
            !said.toString('UTF-8').contains('WARN')
    }

    def 'the diagram is looked for beside the picture, not beside the document'() {
        given: '''Which is only visible where the two differ. A document keeping its pictures in a
                  directory of their own keeps the diagram with them: it belongs to the pictures,
                  and a document that moves does not take it along.'''
            directory.resolve('images/deep').toFile().mkdirs()
            directory.resolve('images/deep/architecture.drawio').toFile().text = SHARED
            directory.resolve('images/deep/architecture-core.svg').toFile().text =
                '<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>'
            def body = bodyOf('<img src="images/deep/architecture-core.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            uploads*.fileName() == ['architecture']
            uploads[0].url().endsWith('images/deep/architecture.drawio')
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')
    }

    def 'a diagram beside the document instead of the picture is not found'() {
        given: 'and the picture stays a picture, because that is what the convention points at'
            sharedDiagram()
            directory.resolve('images/deep').toFile().mkdirs()
            directory.resolve('images/deep/architecture-core.svg').toFile().text =
                '<svg xmlns="http://www.w3.org/2000/svg"><g/></svg>'
            def body = bodyOf('<img src="images/deep/architecture-core.svg" width="700"/>')

        expect:
            !inDirectory().transformImages(body).isEmpty()
            !body.html().contains('drawio')
    }

    def 'the directory decides which reading of a hyphenated name is right'() {
        given: '''architecture-view-core could be the core view of architecture-view or the
                  view-core view of architecture. Only one of those files exists, and that is
                  what settles it - not the name, which cannot.'''
            sharedDiagram()
            view('architecture-view-core.svg')
            def body = bodyOf('<img src="images/architecture-view-core.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            uploads*.fileName() == ['architecture']
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')
    }

    def 'an ordinary hyphenated file name is not a view of anything'() {
        given: '''Which is why a plain hyphen is safe as the separator. Nothing about
                  build-pipeline.svg says diagram, and there is no build.drawio to say it either.'''
            view('build-pipeline.svg')
            def body = bodyOf('<img src="images/build-pipeline.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads*.fileName() == ['build-pipeline.svg']
    }

    def 'a diagram that is there but holds no such page leaves the picture a picture'() {
        given: '''Half the contract is the file being there and half is it answering to the name.
                  A macro pinned to a page that does not exist is worse than a picture: it reads
                  as a page that is wrong rather than one that is missing.'''
            sharedDiagram()
            view('architecture-elsewhere.svg')
            def body = bodyOf('<img src="images/architecture-elsewhere.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            !body.html().contains('drawio')
            uploads*.fileName() == ['architecture-elsewhere.svg']
    }

    def 'a diagram whose own name carries a hyphen is still read correctly'() {
        given: '''Which is why no split rule is hardcoded. Splitting at the first hyphen would
                  only work while the diagram itself is called one word, and a model named
                  my-arch would break it the day somebody renames one.'''
            directory.resolve('images').toFile().mkdirs()
            directory.resolve('images/my-arch.drawio').toFile().text = SHARED
            view('my-arch-view-core.svg')
            def body = bodyOf('<img src="images/my-arch-view-core.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            uploads*.fileName() == ['my-arch']
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')
    }

    def 'and the same name with the generators own shorter form too'() {
        given: 'my-arch-core.svg, where only the tail of the page id is in the name'
            directory.resolve('images').toFile().mkdirs()
            directory.resolve('images/my-arch.drawio').toFile().text = SHARED
            view('my-arch-core.svg')
            def body = bodyOf('<img src="images/my-arch-core.svg" width="700"/>')

        when:
            def uploads = inDirectory().transformImages(body)

        then:
            uploads*.fileName() == ['my-arch']
            body.html().contains('<ac:parameter ac:name="aspect">view-core</ac:parameter>')
    }
}
