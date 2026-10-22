/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.header

import org.docToolchain.html2adoc.Html2Adoc
import org.jsoup.Jsoup
import spock.lang.Specification

/** The upstream HeaderTest. */
class HeaderSpec extends Specification {

    def 'h#level becomes #level equals signs'() {
        expect:
            Html2Adoc.convert("<h${level}>Title</h${level}>") == '=' * level + ' Title\n\n'

        where:
            level << (1..6)
    }

    def 'leading whitespace in the heading is dropped'() {
        expect:
            Html2Adoc.convert('<h1> Title</h1>') == '= Title\n\n'
    }

    def 'a spacer paragraph before the heading leaves no trace'() {
        expect:
            Html2Adoc.convert('<p>&nbsp;</p> <h1>Title</h1>') == '= Title\n\n'
    }

    def 'an emphasised run inside the heading survives: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name     | html                                 | expected
            'start'  | '<h2><em>This</em> is true</h2>'      | '== _This_ is true\n\n'
            'middle' | '<h2>This <em>is</em> true</h2>'      | '== This _is_ true\n\n'
            'end'    | '<h2>This is <em>true</em></h2>'      | '== This is _true_\n\n'
    }

    def 'a quoted heading gains no space after the marker'() {
        expect:
            Html2Adoc.convert('<h2>"Lorem"</h2>') == '== "Lorem"\n\n'
    }

    def 'a named anchor inside the heading is written above it'() {
        expect:
            Html2Adoc.convert('<h2><!--nchpdeb--><a name="chap_7_4">7.4.<!--nchpfin-->Title</a></h2>') ==
                    '[[chap_7_4]]\n== 7.4. Title\n\n'
    }

    def 'a plain link inside the heading produces no empty anchor'() {
        expect:
            Html2Adoc.convert('<h2><a href="#elsewhere">Title</a></h2>') == '== <<elsewhere,Title>>\n\n'
    }

    def 'the shallowest heading lands where the caller asks: #name'() {
        given: '''A caller that writes a heading above the fragment has to say so, or the fragment
                  outranks it: HTML numbers from the top of the document, so <h1> is a level-0
                  section. Shifting all headings by the same amount is what keeps a page that
                  starts at <h2> from skipping a level.'''
        expect:
            Html2Adoc.convert(Jsoup.parse(html).body(), 2).readLines().findAll { it.startsWith('=') } == expected

        where:
            name                   | html                                     | expected
            'h1 first'             | '<h1>A</h1><h2>B</h2>'                   | ['=== A', '==== B']
            'h2 first, no gap'     | '<h2>A</h2><h3>B</h3>'                   | ['=== A', '==== B']
            'a jump is kept'       | '<h2>A</h2><h4>B</h4>'                   | ['=== A', '===== B']
            'deep and alone'       | '<h4>A</h4>'                             | ['=== A']
            'only h6'              | '<h6>A</h6>'                             | ['=== A']
    }

    def 'a heading that structures nothing does not move the page: #name'() {
        given: '''The shift comes from the shallowest heading of the page's own flow. A heading in
                  a table cell or a list item is not a section - and HeaderConverter writes it
                  inside the cell, where a "===" line is text rather than markup - so letting one
                  fix the base level would push every real heading of the page a level deeper.'''
        expect:
            Html2Adoc.convert(Jsoup.parse(html).body(), 2).readLines()
                    .findAll { it.startsWith('=') }.first() == '=== Overview'

        where:
            name            | html
            'in a cell'     | '<h2>Overview</h2><table><tbody><tr><td><h1>K</h1></td></tr></tbody></table>'
            'in a list item'| '<h2>Overview</h2><ul><li><h1>K</h1></li></ul>'
    }

    def 'a heading is never written deeper than AsciiDoc has levels'() {
        given: '''Level 5 is the deepest section, and a seventh marker is a paragraph that begins
                  with one. Clamping is the least bad answer and it is not a free one: the two
                  deepest levels below share one AsciiDoc level and become siblings, so a page
                  deeper than the levels left over loses that much of its structure.'''
        expect:
            Html2Adoc.convert(Jsoup.parse('<h1>A</h1><h5>B</h5><h6>C</h6>').body(), 2)
                    .readLines().findAll { it.startsWith('=') } == ['=== A', '====== B', '====== C']
    }

    def 'without a level the headings are what they always were'() {
        expect: 'a caller that says nothing gets the old behaviour, markers for depth'
            Html2Adoc.convert('<h1>A</h1><h2>B</h2>').readLines().findAll { it.startsWith('=') } ==
                    ['= A', '== B']
    }
}
