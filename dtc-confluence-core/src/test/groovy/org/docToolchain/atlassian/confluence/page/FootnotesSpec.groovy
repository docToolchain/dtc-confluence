package org.docToolchain.atlassian.confluence.page

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * States where footnote definitions end up once a document is split into pages.
 *
 * AsciiDoctor puts them in a div#footnotes next to div#content, which page splitting does not
 * read, so before this they were dropped and every reference pointed nowhere.
 */
class FootnotesSpec extends Specification {

    private static final String WITH_FOOTNOTES = '''
        <h1>The Document</h1>
        <div id="preamble"><div class="sectionbody"><p>Intro text</p></div></div>
        <div id="content">
          <div class="sect1"><h2 id="alpha">Alpha</h2><div class="sectionbody">
            <p>alpha body<sup class="footnote"><a id="_footnoteref_1" href="#_footnotedef_1">1</a></sup></p>
          </div></div>
          <div class="sect1"><h2 id="beta">Beta</h2><div class="sectionbody">
            <p>beta body<sup class="footnote"><a id="_footnoteref_2" href="#_footnotedef_2">2</a></sup></p>
          </div></div>
        </div>
        <div id="footnotes"><hr>
          <div class="footnote" id="_footnotedef_1"><a href="#_footnoteref_1">1</a>. First note.</div>
          <div class="footnote" id="_footnotedef_2"><a href="#_footnoteref_2">2</a>. Second note.</div>
        </div>
    '''

    private static org.jsoup.nodes.Document parse(String html) {
        def dom = Jsoup.parse("<body>${html}</body>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom
    }

    def 'on a single page every definition is carried along'() {
        when:
            def tree = new PageTreeBuilder().build(parse(WITH_FOOTNOTES), '99', 0)

        then:
            tree.pages.size() == 1
            tree.pages[0].body.text().contains('First note.')
            tree.pages[0].body.text().contains('Second note.')
    }

    def 'a definition follows the page that refers to it'() {
        when:
            def tree = new PageTreeBuilder().build(parse(WITH_FOOTNOTES), '99', 1)
            def alpha = tree.pages[0].children[0]
            def beta = tree.pages[0].children[1]

        then: 'each section page carries its own note and not the other one'
            alpha.body.text().contains('First note.')
            !alpha.body.text().contains('Second note.')
            beta.body.text().contains('Second note.')
            !beta.body.text().contains('First note.')

        and: 'the page without a reference gets no footnote block at all'
            tree.pages[0].body.select('div.footnotes').isEmpty()
    }

    def 'the definition becomes an anchor the reference can link to'() {
        when:
            def tree = new PageTreeBuilder().build(parse(WITH_FOOTNOTES), '99', 1)

        then: 'the anchor map knows which page holds the definition'
            tree.anchors['_footnotedef_1'] == 'Alpha'
            tree.anchors['_footnotedef_2'] == 'Beta'

        and: 'and an anchor macro was planted next to it'
            tree.pages[0].children[0].body.html()
                .contains('<ac:parameter ac:name="">_footnotedef_1</ac:parameter>')
    }

    def 'a footnote referenced twice is defined once, on the first page using it'() {
        given: 'both sections point at the same definition'
            def html = WITH_FOOTNOTES
                .replace('href="#_footnotedef_2">2', 'href="#_footnotedef_1">1')

        when:
            def tree = new PageTreeBuilder().build(parse(html), '99', 1)
            def alpha = tree.pages[0].children[0]
            def beta = tree.pages[0].children[1]

        then:
            alpha.body.text().contains('First note.')

        and: 'the second page repeats neither the text nor the anchor'
            !beta.body.text().contains('First note.')
            beta.body.select('div.footnotes').isEmpty()

        and: 'so its reference resolves to the page that does hold it'
            tree.anchors['_footnotedef_1'] == 'Alpha'
    }

    def 'a section keeps the footnote it shares with a sub-section of its own'() {
        given: 'the parent refers to the note first, then a nested section that becomes a page too'
            def html = """
                <h1>The Document</h1>
                <div id="content">
                  <div class="sect1"><h2 id="alpha">Alpha</h2><div class="sectionbody">
                    <p>parent text<sup><a id="_footnoteref_1" href="#_footnotedef_1">1</a></sup></p>
                    <div class="sect2"><h3 id="alpha-one">Alpha One</h3>
                      <p>child text<sup><a href="#_footnotedef_1">1</a></sup></p>
                    </div>
                  </div></div>
                </div>
                <div id="footnotes"><hr>
                  <div class="footnote" id="_footnotedef_1"><a href="#_footnoteref_1">1</a>. Shared note.</div>
                </div>
            """

        when: 'both levels become pages'
            def tree = new PageTreeBuilder().build(parse(html), '99', 2)
            def alpha = tree.pages[0]
            def child = alpha.children[0]

        then: 'the definition stays on the parent, which is published first'
            alpha.body.text().contains('Shared note.')
            !child.body.text().contains('Shared note.')
            tree.anchors['_footnotedef_1'] == 'Alpha'
    }

    def 'a footnote in a heading leaves the page title alone'() {
        given:
            def html = """
                <h1>The Document</h1>
                <div id="content">
                  <div class="sect1">
                    <h2 id="alpha">Alpha<sup class="footnote"><a id="_footnoteref_1" href="#_footnotedef_1">1</a></sup></h2>
                    <div class="sectionbody"><p>body</p></div>
                  </div>
                </div>
                <div id="footnotes"><hr>
                  <div class="footnote" id="_footnotedef_1"><a href="#_footnoteref_1">1</a>. Heading note.</div>
                </div>
            """

        when:
            def tree = new PageTreeBuilder().build(parse(html), '99', 1)
            def alpha = tree.pages[0]

        then: 'the number does not become part of the name, which also decides page identity'
            alpha.title == 'Alpha'

        and: """A page title is plain text, so the reference cannot survive it. The definition is
                then attached nowhere rather than sitting on a page with nothing pointing at it -
                a known loss, recorded in docs/decisions.adoc."""
            !alpha.body.text().contains('Heading note.')
            tree.anchors['_footnotedef_1'] == null
    }

    def 'a document without footnotes is left untouched'() {
        given:
            def html = '''
                <h1>The Document</h1>
                <div id="content">
                  <div class="sect1"><h2 id="alpha">Alpha</h2>
                    <div class="sectionbody"><p>no notes here</p></div></div>
                </div>
            '''

        when:
            def tree = new PageTreeBuilder().build(parse(html), '99', 1)

        then:
            tree.pages[0].body.select('div.footnotes').isEmpty()
    }

    def 'the definitions are not published a second time as part of the document body'() {
        when: 'the whole document goes onto one page'
            def tree = new PageTreeBuilder().build(parse(WITH_FOOTNOTES), '99', 0)

        then: 'the note appears once, in the block appended to the page'
            tree.pages[0].body.select('div.footnotes').size() == 1
            tree.pages[0].body.text().count('First note.') == 1
    }
}
