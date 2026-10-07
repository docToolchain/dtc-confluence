/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.list

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream ListTest. */
class ListSpec extends Specification {

    def 'an unordered list becomes asterisks'() {
        expect:
            Html2Adoc.convert('<ul><li>lorem</li><li>ipsum</li></ul>') == '* lorem\n* ipsum\n\n'
    }

    def 'an ordered list becomes dots'() {
        expect:
            Html2Adoc.convert('<ol><li>lorem</li><li>ipsum</li></ol>') == '. lorem\n. ipsum\n\n'
    }

    def 'a line break in the list markup is ignored: #name'() {
        expect:
            Html2Adoc.convert(html) == '* lorem\n* ipsum\n\n'

        where:
            name                   | html
            'between ul and li'    | '<ul><br><li>lorem</li><li>ipsum</li></ul>'
            'inside the first li'  | '<ul><li><br>lorem</li><li>ipsum</li></ul>'
    }

    def 'punctuation at the start of an item gains no space after the bullet'() {
        expect:
            Html2Adoc.convert('<ul><li>"lorem"</li><li>...</li></ul>') == '* "lorem"\n* ...\n\n'
    }
}
