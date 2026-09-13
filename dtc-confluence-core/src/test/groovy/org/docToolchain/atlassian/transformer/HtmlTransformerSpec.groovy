package org.docToolchain.atlassian.transformer

import org.docToolchain.util.TestUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import spock.lang.Specification

class HtmlTransformerSpec extends Specification {

    static final String TEST_RESOURCE_PATH = "/fixtures/atlassian/transformer/htmlTransformer"

    HtmlTransformer htmlTransformer

    def "test html transformation"() {
        setup: "we initialize the transformer"
            htmlTransformer = new HtmlTransformer()
            File expectedHtmlFile = new File("${TestUtils.TEST_RESOURCES_DIR}${TEST_RESOURCE_PATH}/1355-source-code-format-transformed.html")
            String transformedHtml = expectedHtmlFile.text
        when: 'we pass a HTML section into the transformer'
            File htmlFile = new File("${TestUtils.TEST_RESOURCES_DIR}${TEST_RESOURCE_PATH}/1355-source-code-format.html")
            Element input = new Document("").outputSettings(new Document.OutputSettings().prettyPrint(false)).html(htmlFile.text)
            def result = htmlTransformer.transformToConfluenceFormat(input, [:], [:], "", "")
        then:  'there is no exception, newlines are preserved and the code has the expected Confluence structure'
            noExceptionThrown()
            // need to call trim() to avoid whitespace issues, newlines are still preserved. Hence this is sane.
            result.trim() == transformedHtml.trim()
    }

    def "a code sample naming an HTML tag is published as written"() {
        given: 'a listing whose text mentions br and hr, which Jsoup escapes on the way out'
            def body = Jsoup.parse('<div><div class="listingblock"><div class="content">' +
                '<pre class="highlight"><code class="language-html">' +
                'a &lt;br&gt; and an &lt;hr&gt; in a code sample' +
                '</code></pre></div></div></div>', '', Parser.xmlParser())
            body.outputSettings().prettyPrint(false)

        when:
            def result = new HtmlTransformer()
                .transformToConfluenceFormat(body.selectFirst('div'), [:], [:], '', '')

        then: 'the sample keeps the tags it names, rather than being rewritten into XHTML'
            result.contains('a <br> and an <hr> in a code sample')
            !result.contains('<br />')
            !result.contains('<hr />')
    }

    def "a real line break is still serialised as XHTML"() {
        given:
            def body = Jsoup.parse('<div><p>one<br>two</p><hr></div>', '', Parser.xmlParser())
            body.outputSettings().prettyPrint(false)

        when:
            def result = new HtmlTransformer()
                .transformToConfluenceFormat(body.selectFirst('div'), [:], [:], '', '')

        then: 'Confluence storage format is XHTML, so these have to be closed'
            result.contains('<br />')
            result.contains('<hr />')
    }
}
