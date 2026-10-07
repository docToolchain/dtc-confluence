/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.header

import org.docToolchain.html2adoc.Html2Adoc
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
}
