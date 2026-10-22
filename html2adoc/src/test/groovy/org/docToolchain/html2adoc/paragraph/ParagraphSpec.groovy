/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.paragraph

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream ParagraphTest. */
class ParagraphSpec extends Specification {

    def 'a paragraph becomes its text and a blank line'() {
        expect:
            Html2Adoc.convert('<p>A simple paragraph</p>') == 'A simple paragraph\n\n'
    }

    def 'two paragraphs stay two'() {
        expect:
            Html2Adoc.convert('<p>First</p><p>Second</p>') == 'First\n\nSecond\n\n'
    }

    def 'a paragraph holding no content is dropped: #name'() {
        expect:
            Html2Adoc.convert(html) == ''

        where:
            name                   | html
            'non-breaking space'   | '<p>&nbsp;</p>'
            'a space'              | '<p> </p>'
            'nothing at all'       | '<p></p>'
    }

    def 'a line break inside a paragraph becomes a space: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                  | html                                        | expected
            'at the start'        | '<p><br>Lorem Ipsum</p>'                    | 'Lorem Ipsum\n\n'
            'in the middle'       | '<p>Lorem<br> Ipsum</p>'                    | 'Lorem Ipsum\n\n'
            'at the end'          | '<p>Lorem Ipsum<br></p>'                    | 'Lorem Ipsum\n\n'
            'before a strong run' | '<p>Lorem<br> <strong>Ipsum</strong></p>'   | 'Lorem *Ipsum*\n\n'
            'starting the second' | '<p>First</p><p> <br>Second</p>'            | 'First\n\nSecond\n\n'
    }
}
