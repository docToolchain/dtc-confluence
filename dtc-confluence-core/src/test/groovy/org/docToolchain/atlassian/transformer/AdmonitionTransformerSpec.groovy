package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification
import spock.lang.Unroll

class AdmonitionTransformerSpec extends Specification {

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private static String admonition(String kind, String title = '') {
        def titleMarkup = title ? "<div class=\"title\">${title}</div>" : ''
        return "<div class=\"admonitionblock ${kind}\"><div class=\"content\">" +
            "${titleMarkup}<p>the text</p></div></div>"
    }

    @Unroll
    def 'an AsciiDoctor #kind becomes the Confluence #macro macro'() {
        given:
            def body = bodyOf(admonition(kind))

        when:
            new AdmonitionTransformer().transformAdmonitions(body)

        then:
            body.html().contains("<ac:structured-macro ac:name=\"${macro}\">")
            body.html().contains('the text')

        and: 'the original block is gone'
            body.select('.admonitionblock').isEmpty()

        where:
            kind        || macro
            'note'      || 'info'
            'tip'       || 'tip'
            'warning'   || 'warning'
            'important' || 'warning'
            'caution'   || 'note'
    }

    def 'a block title becomes the macro title and leaves the body'() {
        given:
            def body = bodyOf(admonition('note', 'Mind this'))

        when:
            new AdmonitionTransformer().transformAdmonitions(body)

        then:
            body.html().contains('<ac:parameter ac:name="title">Mind this</ac:parameter>')

        and: 'the title is not repeated inside the text'
            !body.html().contains('<div class="title">')
    }

    def 'a block without a title still carries an empty title parameter'() {
        given:
            def body = bodyOf(admonition('note'))

        when:
            new AdmonitionTransformer().transformAdmonitions(body)

        then: """Pins current behaviour. Confluence accepts an empty title and the published pages
                 have always carried one - the smoke test against ASF showed
                 <ac:parameter ac:name="title" /> on every admonition."""
            body.html().contains('<ac:parameter ac:name="title"></ac:parameter>')
    }

    def 'two admonitions of different kinds are both transformed'() {
        given:
            def body = bodyOf(admonition('note') + admonition('warning'))

        when:
            new AdmonitionTransformer().transformAdmonitions(body)

        then:
            body.select('ac|structured-macro').size() == 2
    }
}
