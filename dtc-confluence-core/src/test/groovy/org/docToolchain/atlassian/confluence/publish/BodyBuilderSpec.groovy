package org.docToolchain.atlassian.confluence.publish

import org.docToolchain.configuration.ConfigService
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States what a dry run changes about the body that is built, which is nothing.
 *
 * The hash a dry run compares is the hash of the body a real run would send, so the body has to
 * be built either way - and building it names every embedded image, because the name of such an
 * image is the hash of its content. Naming one is computation; writing it is not.
 *
 * It also states which transformers a built body has been through, for the ones whose absence
 * would be a silent loss rather than a visible one.
 */
class BodyBuilderSpec extends Specification {

    @TempDir
    Path documents

    /** A one-pixel PNG inlined as a data URI, which is the path that reaches the image store. */
    private static final String EMBEDDED =
        '<div class="imageblock"><div class="content">' +
            '<img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ' +
            'AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" alt="Dot"></div></div>'

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private BodyBuilder builder(Object dryRun) {
        def config = new ConfigObject()
        config.confluence = [api: 'https://confluence.example/rest/api/', credentials: 'x']
        if (dryRun != null) {
            config.confluence.dryRun = dryRun
        }
        return new BodyBuilder(new ConfigService(config),
            documents.resolve('manual.html').toString(), '', '')
    }

    private File imageDirectory() {
        return new File(documents.toFile(), 'confluence/images')
    }

    def 'a dry run builds the body a real run would send, and writes no image'() {
        when: 'the dry run first, so that no file it could have found is there'
            def dry = builder(true).build(bodyOf(EMBEDDED), [:], [:])

        then: 'it named the image - the name is the hash of the content, so naming needs no file'
            dry.storageFormat().contains('ri:attachment')
            dry.uploads().size() == 1

        and: 'and wrote nothing'
            !imageDirectory().exists()

        when: 'the same document for real'
            def real = builder(null).build(bodyOf(EMBEDDED), [:], [:])

        then: 'the body is the same, attachment name included'
            dry.storageFormat() == real.storageFormat()
            dry.uploads()*.fileName() == real.uploads()*.fileName()

        and: 'and this time the file is there, under the name both bodies refer to'
            def written = imageDirectory().listFiles().findAll { it.name.endsWith('.png') }
            written*.name == real.uploads()*.fileName()
    }

    def 'a dry run configured as text writes nothing either'() {
        given: """A setting arrives from a YAML file, a Groovy file or the command line, and only
                  the first of those necessarily carries a type."""
        when:
            def dry = builder('true').build(bodyOf(EMBEDDED), [:], [:])

        then:
            dry.storageFormat().contains('ri:attachment')
            !imageDirectory().exists()
    }

    def 'the body carries back the anchor of an inline comment'() {
        when: 'the span the export left for a marked phrase, as Asciidoctor renders it'
            def built = builder(null).build(bodyOf('<p>see <span class="inline-comment-marker ' +
                'comment-ref-6cc14366-cefd-4ae3-af80-9f5839ca348c">these words</span> here</p>'),
                [:], [:])

        then: '''as the element that anchors the comment: a publish replaces the whole body, so a
                 comment whose marker this leaves out is left dangling on the page'''
            built.storageFormat().contains('<ac:inline-comment-marker ' +
                'ac:ref="6cc14366-cefd-4ae3-af80-9f5839ca348c">these words' +
                '</ac:inline-comment-marker>')
    }

    def 'a run that is not a dry run writes the image'() {
        when: 'the flag spelled out as false, which is a decision rather than an absence'
            def real = builder(false).build(bodyOf(EMBEDDED), [:], [:])

        then:
            imageDirectory().listFiles().findAll { it.name.endsWith('.png') }*.name ==
                real.uploads()*.fileName()
    }
}
