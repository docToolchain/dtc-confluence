package org.docToolchain.atlassian.confluence.page

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * States the page-splitting rules on small documents. The golden-file transcripts cover the same
 * code on a real document; these say what the rules are.
 */
class PageTreeBuilderSpec extends Specification {

    private static final String TWO_SECTIONS = '''
        <h1>The Document</h1>
        <div id="preamble"><div class="sectionbody"><p id="intro">Intro text</p></div></div>
        <div id="content">
          <div class="sect1"><h2 id="alpha">Alpha</h2><div class="sectionbody">
            <p>alpha body</p>
            <div class="sect2"><h3 id="alpha-one">Alpha One</h3><p>deeper</p></div>
          </div></div>
          <div class="sect1"><h2 id="beta">Beta</h2><div class="sectionbody"><p>beta body</p></div></div>
        </div>
    '''

    private static org.jsoup.nodes.Document parse(String html) {
        def dom = Jsoup.parse("<body>${html}</body>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom
    }

    def 'level zero puts the whole document on one page'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), '99', 0)

        then:
            tree.pages.size() == 1
            tree.pages[0].title == 'The Document'
            tree.pages[0].parent == '99'
            tree.pages[0].children.isEmpty()

        and: 'both sections are still in that one body'
            tree.pages[0].body.text().contains('alpha body')
            tree.pages[0].body.text().contains('beta body')
    }

    def 'level one gives the preamble a page, with one child per section'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), '99', 1)

        then: 'the preamble is the page everything else hangs under'
            tree.pages.size() == 1
            tree.pages[0].title == 'The Document'
            tree.pages[0].parent == '99'

        and:
            tree.pages[0].children*.title == ['Alpha', 'Beta']

        and: 'a sub-section stays inside its section at this level'
            tree.pages[0].children[0].children.isEmpty()
            tree.pages[0].children[0].body.text().contains('deeper')
    }

    def 'level two splits sub-sections off as well'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), '99', 2)

        then:
            tree.pages[0].children*.title == ['Alpha', 'Beta']
            tree.pages[0].children[0].children*.title == ['Alpha One']

        and: 'and the sub-section is no longer part of its parent page'
            !tree.pages[0].children[0].body.text().contains('deeper')
    }

    def 'a document without a preamble yields the sections directly'() {
        given:
            def html = '''
                <h1>No Preamble</h1>
                <div id="content">
                  <div class="sect1"><h2>Only</h2><div class="sectionbody"><p>body</p></div></div>
                </div>
            '''

        when:
            def tree = new PageTreeBuilder().build(parse(html), '99', 1)

        then:
            tree.pages*.title == ['Only']
            tree.pages[0].parent == '99'
    }

    def 'only the first page of a document keeps the requested parent'() {
        given: 'two top-level bodies at level zero'
            def html = '''
                <h1>T</h1>
                <div id="content"><p>one</p></div>
                <div id="content"><p>two</p></div>
            '''

        when:
            def tree = new PageTreeBuilder().build(parse(html), '99', 0)

        then: 'the rest go to the root of the space'
            tree.pages*.parent == ['99', null]
    }

    def 'every element id becomes an anchor macro and is recorded against its page'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), null, 1)

        then: 'the ids are mapped to the title of the page they ended up on'
            tree.anchors['intro'] == 'The Document'
            tree.anchors['alpha-one'] == 'Alpha'

        and: """A section heading is not part of its own page body: at level 1 the body is the
                 section's div.sectionbody, and the heading sits beside it. Heading ids are
                 therefore recorded as page anchors, not as in-page anchors."""
            tree.anchors['alpha'] == null
            tree.pageAnchors['alpha'] == 'Alpha'

        and: 'and the document now carries a Confluence anchor before each of them'
            tree.pages[0].body.html().contains('<ac:structured-macro ac:name="anchor">')
    }

    def 'section headings are recorded separately, as they name a page'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), null, 2)

        then:
            tree.pageAnchors['alpha'] == 'Alpha'
            tree.pageAnchors['alpha-one'] == 'Alpha One'
    }

    def 'a heading without an id records nothing'() {
        given:
            def html = '<h1>T</h1><div id="content"><div class="sect1"><h2>No Id</h2>' +
                '<div class="sectionbody"><p>b</p></div></div></div>'

        when:
            def tree = new PageTreeBuilder().build(parse(html), null, 1)

        then:
            tree.pageAnchors.isEmpty()
    }

    def 'headings are promoted so a split-off page reads as a document of its own'() {
        when:
            def tree = new PageTreeBuilder().build(parse(TWO_SECTIONS), null, 2)

        then: 'the h3 of the sub-section became an h2 on its own page'
            def alphaOne = tree.pages[0].children[0].children[0]
            alphaOne.body.select('h3').isEmpty()
    }
}
