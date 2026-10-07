/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.format

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * The upstream EmphasisTest, MonospaceTest, SubscriptTest and SuperscriptTest, which were one case
 * each of the same shape, plus the first case of StrongTest.
 */
class InlineFormatSpec extends Specification {

    def 'an inline #tag becomes #marker around the word'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            tag      | html                                | expected
            'em'     | '<p>This <em>is</em> important</p>'  | 'This _is_ important\n\n'
            'code'   | '<p>This <code>is</code> important</p>' | 'This `is` important\n\n'
            'strong' | '<p>This <strong>is</strong> important</p>' | 'This *is* important\n\n'
            'sub'    | '<p>The <sub>hello</sub> world</p>'  | 'The ~hello~ world\n\n'
            'sup'    | '<p>The <sup>hello</sup> world</p>'  | 'The ^hello^ world\n\n'

            marker = expected.find(/[_`*~^]/)
    }
}
