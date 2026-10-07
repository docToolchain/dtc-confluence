/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.unknown

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream UnknownTest: markup no converter claims disappears and its text stays. */
class UnknownSpec extends Specification {

    def 'an unknown element inside a paragraph leaves only its text'() {
        expect:
            Html2Adoc.convert('<p>A <xx>simple</xx> paragraph</p>') == 'A simple paragraph\n\n'
    }

    def 'two adjacent unknown elements are still two words'() {
        expect:
            Html2Adoc.convert('<yyy>Lorem</yyy><xx>Ipsum</xx>') == 'Lorem Ipsum'
    }

    def 'two adjacent unknown elements inside a paragraph are still two words'() {
        expect:
            Html2Adoc.convert('<p><yyy>Lorem</yyy><xx>Ipsum</xx></p>') == 'Lorem Ipsum\n\n'
    }
}
