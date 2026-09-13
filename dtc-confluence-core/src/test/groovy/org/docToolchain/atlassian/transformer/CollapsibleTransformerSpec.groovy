package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

class CollapsibleTransformerSpec extends Specification {

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    def 'a collapsible block becomes an expand macro'() {
        given:
            def body = bodyOf('<details><summary class="title">Guidance</summary>' +
                '<div class="content"><p>hidden until opened</p></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then:
            body.html().contains('<ac:structured-macro ac:name="expand">')
            body.html().contains('<ac:parameter ac:name="title">Guidance</ac:parameter>')
            body.html().contains('hidden until opened')

        and: 'the details element is gone'
            body.select('details').isEmpty()
            body.select('summary').isEmpty()
    }

    def 'the summary is used as the title and not repeated in the body'() {
        given:
            def body = bodyOf('<details><summary class="title">Only once</summary>' +
                '<div class="content"><p>text</p></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then:
            body.html().count('Only once') == 1
    }

    def 'a block without a summary gets a usable default title'() {
        given:
            def body = bodyOf('<details><div class="content"><p>text</p></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then: 'a macro without a title renders as an unlabelled fold, which is worse than a generic one'
            body.html().contains('<ac:parameter ac:name="title">Details</ac:parameter>')
    }

    def 'a block that AsciiDoctor rendered open is folded like any other'() {
        given:
            def body = bodyOf('<details open><summary class="title">Starts open</summary>' +
                '<div class="content"><p>text</p></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then: """Pins a deliberate loss: a Confluence expand macro is always folded, so
                 [%collapsible%open] cannot keep its initial state. The fold matters more."""
            body.html().contains('ac:name="expand"')
            !body.html().contains('open=')
    }

    def 'markup inside the block survives'() {
        given:
            def body = bodyOf('<details><summary class="title">With content</summary>' +
                '<div class="content"><ul><li>first</li></ul>' +
                '<ac:structured-macro ac:name="info"><ac:rich-text-body>note</ac:rich-text-body>' +
                '</ac:structured-macro></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then: 'an admonition already converted stays converted inside the fold'
            body.html().contains('<li>first</li>')
            body.html().contains('ac:name="info"')
    }

    def 'a title carrying markup characters is escaped'() {
        given:
            def body = bodyOf('<details><summary class="title">a &amp; b</summary>' +
                '<div class="content"><p>text</p></div></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then:
            body.html().contains('a &amp; b</ac:parameter>')
    }

    def 'several blocks are each transformed'() {
        given:
            def body = bodyOf('<details><summary>One</summary><p>a</p></details>' +
                '<details><summary>Two</summary><p>b</p></details>')

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then:
            body.select('ac|structured-macro').size() == 2
            body.select('details').isEmpty()
    }

    def 'a document without collapsible blocks is untouched'() {
        given:
            def body = bodyOf('<p>ordinary</p>')
            def before = body.html()

        when:
            new CollapsibleTransformer().transformCollapsibles(body)

        then:
            body.html() == before
    }
}
