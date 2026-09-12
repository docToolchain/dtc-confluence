package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * Pins the link rewriting before it is ported to Java. Every expectation here was taken from the
 * Groovy implementation's actual output, so a difference after the port is a regression and not a
 * disagreement about intent.
 */
class LinkTransformerSpec extends Specification {

    private static final String JIRA_API = 'https://jira.example.com/rest/api/2'

    private Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    def 'an anchor known to the document becomes a link carrying that anchor'() {
        given:
            def body = bodyOf('<a href="#sect-two">Section Two</a>')

        when:
            new LinkTransformer().transformLinks(body, ['sect-two': 'Target Page'], [:], '', '', JIRA_API, null)

        then: 'the link text is moved into the CDATA placeholder, which sanitizeBody turns into CDATA'
            body.html() == '<ac:link ac:anchor="sect-two">' +
                '<ri:page ri:content-title="Target Page" />' +
                '<ac:plain-text-link-body><cdata-placeholder>Section Two</cdata-placeholder></ac:plain-text-link-body>' +
                '</ac:link>'
    }

    def 'an anchor that only names a page produces a link without an anchor attribute'() {
        given:
            def body = bodyOf('<a href="#page-three">Page Three</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], ['page-three': 'Page Three Title'], '', '', JIRA_API, null)

        then:
            body.html().startsWith('<ac:link><ri:page ri:content-title="Page Three Title" />')
    }

    def 'page prefix and suffix are applied to the referenced title'() {
        given:
            def body = bodyOf('<a href="#sect">Text</a>')

        when:
            new LinkTransformer().transformLinks(body, ['sect': 'Title'], [:], 'PRE-', '-SUF', JIRA_API, null)

        then:
            body.html().contains('ri:content-title="PRE-Title-SUF"')
    }

    def 'an unknown anchor is left alone'() {
        given:
            def body = bodyOf('<a href="#nowhere">Text</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', JIRA_API, null)

        then:
            body.html() == '<a href="#nowhere">Text</a>'
    }

    def 'a link without text is left alone, because Confluence needs link text'() {
        given:
            def body = bodyOf('<a href="#sect"></a>')

        when:
            new LinkTransformer().transformLinks(body, ['sect': 'Title'], [:], '', '', JIRA_API, null)

        then:
            body.html() == '<a href="#sect"></a>'
    }

    def 'a Jira browse link becomes a Jira macro keyed by the ticket'() {
        given:
            def body = bodyOf('<a href="https://jira.example.com/browse/ABC-1">ABC-1</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', JIRA_API, null)

        then:
            body.html().contains('<ac:structured-macro ac:name="jira" ac:schema-version="1">')
            body.html().contains('<ac:parameter ac:name="key">ABC-1</ac:parameter>')

        and: 'no server id is emitted when none is configured'
            !body.html().contains('serverId')

        and: 'the original anchor is gone'
            body.select('a').isEmpty()
    }

    def 'an on-premise server id is added to the macro when configured'() {
        given:
            def body = bodyOf('<a href="https://jira.example.com/browse/ABC-1">ABC-1</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', JIRA_API, 'SRV-9')

        then:
            body.html().contains('<ac:parameter ac:name="serverId">SRV-9</ac:parameter>')
    }

    def 'the Jira base URL is derived from the REST API URL, dropping the default port'() {
        given:
            def body = bodyOf('<a href="https://jira.example.com/browse/ABC-1">ABC-1</a>')

        when: 'the API URL carries the default port explicitly'
            new LinkTransformer().transformLinks(body, [:], [:], '', '', 'https://jira.example.com:443/rest/api/2', null)

        then: 'the link is still recognised, so the port was not part of the comparison'
            body.select('a').isEmpty()
    }

    def 'a non-default port is part of the Jira base URL'() {
        given:
            def body = bodyOf('<a href="https://jira.example.com:8443/browse/ABC-1">ABC-1</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', 'https://jira.example.com:8443/rest/api/2', null)

        then:
            body.select('a').isEmpty()
    }

    def 'links to other hosts are left alone'() {
        given:
            def body = bodyOf('<a href="https://elsewhere.example/browse/ABC-1">elsewhere</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', JIRA_API, null)

        then:
            body.html() == '<a href="https://elsewhere.example/browse/ABC-1">elsewhere</a>'
    }

    def 'without a configured Jira API nothing is rewritten as a Jira macro'() {
        given:
            def body = bodyOf('<a href="https://jira.example.com/browse/ABC-1">ABC-1</a>')

        when:
            new LinkTransformer().transformLinks(body, [:], [:], '', '', null, null)

        then:
            body.html() == '<a href="https://jira.example.com/browse/ABC-1">ABC-1</a>'
    }
}
