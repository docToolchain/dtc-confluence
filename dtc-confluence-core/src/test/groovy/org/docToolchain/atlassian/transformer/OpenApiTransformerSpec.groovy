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

        then: 'the macro fetches it itself, so the listing is not sent along'
            body.html().contains('<ac:parameter ac:name="url">https://example.org/api.yaml</ac:parameter>')
            !body.html().contains('showDownloadButton')
            !body.html().contains('cdata-placeholder')
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
}
