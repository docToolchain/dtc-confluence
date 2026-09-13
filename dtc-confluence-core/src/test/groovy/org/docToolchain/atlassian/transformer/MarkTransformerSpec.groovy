package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

class MarkTransformerSpec extends Specification {

    def 'highlighted text becomes inline styling, because Confluence strips mark'() {
        given:
            def dom = Jsoup.parse('<div><p>a <mark>highlight</mark> here</p></div>', '', Parser.xmlParser())
            dom.outputSettings().prettyPrint(false)
            def body = dom.selectFirst('div')

        when:
            new MarkTransformer().transformMarks(body)

        then:
            body.select('mark').isEmpty()
            body.html().contains('background:#ff0')
            body.text().contains('highlight')
    }

    def 'a body without highlights is left alone'() {
        given:
            def dom = Jsoup.parse('<div><p>plain</p></div>', '', Parser.xmlParser())
            def body = dom.selectFirst('div')
            def before = body.html()

        when:
            new MarkTransformer().transformMarks(body)

        then:
            body.html() == before
    }
}
