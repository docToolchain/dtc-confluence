package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Three Confluence plugins render OpenAPI and none of them is installed everywhere, so which macro
 * to emit is configured per site. The golden transcripts do not reach this code - the smoke
 * document has no OpenAPI listing - so it is covered here directly.
 */
class OpenApiTransformerSpec extends Specification {

    private static final String LISTING = '''
        <div class="openapi listingblock">
          <pre><code>openapi: 3.0.0
info:
  title: Example
</code></pre>
        </div>
    '''

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    @Unroll
    def 'a setting of #configured emits the #macro macro'() {
        given:
            def body = bodyOf(LISTING)

        when:
            new OpenApiTransformer(configured).transformOpenApi(body)

        then:
            body.html().contains("ac:name=\"${macro}\"")

        and: 'the listing travels inside a CDATA placeholder, unescaped'
            body.html().contains('<cdata-placeholder>')
            body.html().contains('openapi: 3.0.0')

        where:
            configured              || macro
            true                    || 'confluence-open-api'
            'confluence-open-api'   || 'confluence-open-api'
            'swagger-open-api'      || 'swagger-open-api'
            'open-api'              || 'open-api'
    }

    @Unroll
    def 'a setting of #configured leaves the listing alone'() {
        given:
            def body = bodyOf(LISTING)
            def before = body.html()

        when:
            new OpenApiTransformer(configured).transformOpenApi(body)

        then: 'without a macro configured, it stays an ordinary code block'
            body.html() == before

        where:
            configured << [null, false, '', 'something-else']
    }

    def 'the open-api macro offers a download button by default'() {
        given:
            def body = bodyOf(LISTING)

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then:
            body.html().contains('<ac:parameter ac:name="showDownloadButton">true</ac:parameter>')
    }

    def 'a url on the listing block is passed to the macro instead of the document'() {
        given: 'the block carries the document location as a class'
            def body = bodyOf('''
                <div class="listingblock openapi url:https://example.org/api.yaml">
                  <pre><code>openapi: 3.0.0</code></pre>
                </div>
            ''')

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then:
            body.html().contains('<ac:parameter ac:name="url">https://example.org/api.yaml</ac:parameter>')
            !body.html().contains('showDownloadButton')
            !body.html().contains('cdata-placeholder')

        and: """The listing itself is still sent along with the URL, as it was in the Groovy.
                 Whether the plugin ignores it is untested - it is installed on no instance
                 available here - so this pins current behaviour rather than intent."""
            body.html().contains('openapi: 3.0.0')
    }

    def 'each listing takes the url from its own block'() {
        given: 'two API documents on one page, one with a url and one without'
            def body = bodyOf('''
                <div class="listingblock openapi url:https://example.org/first.yaml">
                  <pre><code>openapi: first</code></pre>
                </div>
                <div class="listingblock openapi">
                  <pre><code>openapi: second</code></pre>
                </div>
            ''')

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then: """The Groovy computed one URL for the whole body and kept the last it found, so
                 both macros pointed at the same document and the inline listing was turned into a
                 fetch of a URL that was never meant for it."""
            body.html().contains('<ac:parameter ac:name="url">https://example.org/first.yaml</ac:parameter>')
            body.html().count('ac:name="url"') == 1

        and: 'the listing without a url keeps its download button and its content'
            body.html().contains('showDownloadButton')
            body.html().contains('openapi: second')
    }

    def 'two listings with different urls each keep their own'() {
        given:
            def body = bodyOf('''
                <div class="listingblock openapi url:https://example.org/a.yaml">
                  <pre><code>openapi: a</code></pre>
                </div>
                <div class="listingblock openapi url:https://example.org/b.yaml">
                  <pre><code>openapi: b</code></pre>
                </div>
            ''')

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then:
            body.html().contains('ac:name="url">https://example.org/a.yaml<')
            body.html().contains('ac:name="url">https://example.org/b.yaml<')
    }

    def 'a document without an OpenAPI listing is untouched'() {
        given:
            def body = bodyOf('<div class="listingblock"><pre><code>not openapi</code></pre></div>')
            def before = body.html()

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then:
            body.html() == before
    }
    def 'an interpolated setting is recognised, not silently ignored'() {
        given: 'the setting comes from an executable Groovy configuration'
            def interpolated = "${'open-api'}"
            def body = bodyOf(LISTING)

        expect: 'an interpolated value is a GString, and String.equals answers false for one'
            !(interpolated instanceof String)

        when:
            new OpenApiTransformer(interpolated).transformOpenApi(body)

        then: 'it is matched by value, so the transformation is not silently switched off'
            body.html().contains('ac:name="open-api"')
    }

    def 'a CDATA terminator inside the document does not close the section early'() {
        given: 'a description carrying the terminator, which a valid OpenAPI document may'
            def body = bodyOf('<div class="openapi listingblock"><pre><code>' +
                'info:\n  description: "ends with ]]&gt; in the middle"\n' +
                '</code></pre></div>')

        when:
            new OpenApiTransformer('confluence-open-api').transformOpenApi(body)

        then: '''the terminator is split, so the storage format stays well formed. Asserted on the
                  text rather than the markup: the document is inserted as a text node, so Jsoup
                  escapes it here and HtmlTransformer unescapes it again inside the placeholder.'''
            body.text().contains(']]]]><![CDATA[>')
    }

    def 'only a url: class names a document, not every class starting with url'() {
        given: 'a role that merely begins with the same letters'
            def body = bodyOf('<div class="listingblock openapi url-button">' +
                '<pre><code>openapi: 3.0.0</code></pre></div>')

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then: 'it is not mistaken for a document location'
            !body.html().contains('ac:name="url"')
            body.html().contains('showDownloadButton')
    }

    def 'a url containing the prefix again keeps it'() {
        given:
            def body = bodyOf('<div class="listingblock openapi url:https://example.org/url:x/api.yaml">' +
                '<pre><code>openapi: 3.0.0</code></pre></div>')

        when:
            new OpenApiTransformer('open-api').transformOpenApi(body)

        then: 'only the leading prefix is removed'
            body.html().contains('ac:name="url">https://example.org/url:x/api.yaml<')
    }
}
