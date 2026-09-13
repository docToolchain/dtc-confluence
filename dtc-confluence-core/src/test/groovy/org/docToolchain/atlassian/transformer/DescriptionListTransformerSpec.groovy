package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * Confluence storage format has no description list, so one becomes a table. The interesting part
 * is what happens when terms and definitions do not come in pairs.
 */
class DescriptionListTransformerSpec extends Specification {

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private static org.jsoup.nodes.Element transform(String listHtml) {
        def body = bodyOf(listHtml)
        new DescriptionListTransformer().transformDescriptionLists(body)
        return body
    }

    def 'a list becomes a table, terms as header cells'() {
        when:
            def body = transform('<dl><dt>Term</dt><dd>Definition</dd></dl>')

        then:
            body.select('table').size() == 1
            body.select('dl').isEmpty()
            body.select('tr').size() == 1
            body.select('th').text() == 'Term'
            body.select('td').text() == 'Definition'
    }

    def 'several pairs become several rows'() {
        when:
            def body = transform('<dl><dt>A</dt><dd>1</dd><dt>B</dt><dd>2</dd></dl>')

        then:
            body.select('tr').size() == 2
            body.select('th')*.text() == ['A', 'B']
            body.select('td')*.text() == ['1', '2']
    }

    def 'one term with two definitions spans the term across both rows'() {
        when:
            def body = transform('<dl><dt>A</dt><dd>1</dd><dd>2</dd></dl>')

        then:
            body.select('tr').size() == 2
            body.select('th').size() == 1
            body.select('th').first().attr('rowspan') == '2'
            body.select('td')*.text() == ['1', '2']
    }

    def 'two terms with one definition span the definition instead'() {
        when:
            def body = transform('<dl><dt>A</dt><dt>B</dt><dd>1</dd></dl>')

        then:
            body.select('tr').size() == 2
            body.select('th')*.text() == ['A', 'B']
            body.select('td').first().attr('rowspan') == '2'
    }

    def 'a term without a definition gets an empty cell'() {
        when:
            def body = transform('<dl><dt>A</dt></dl>')

        then:
            body.select('th').text() == 'A'
            body.select('td').size() == 1
            body.select('td').text() == ''
    }

    def 'wrapping divs are removed, as they carry no meaning'() {
        when:
            def body = transform('<dl><div><dt>A</dt><dd>1</dd></div></dl>')

        then:
            body.select('div div').isEmpty()
            body.select('th').text() == 'A'
    }
}
