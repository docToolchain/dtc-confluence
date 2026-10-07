/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.escape

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * Which of AsciiDoc's two quoting forms a formatted run is written in.
 *
 * <p>{@code *bold*} is the constrained form and is only recognised where neither of its characters
 * runs into a word. Where one does, the run has to be written {@code **bold**}, and a converter
 * that always wrote the short form would silently drop the formatting it was asked for.</p>
 */
class QuotingFormSpec extends Specification {

    def 'the constrained form is used where it is recognised: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                 | html                                           | expected
            'after a space'      | '<p>This <strong>is</strong> important</p>'     | 'This *is* important\n\n'
            'after a hyphen'     | '<p>dtc-<strong>confluence</strong> here</p>'   | 'dtc-*confluence* here\n\n'
            'at the start'       | '<p><strong>Bold</strong>, then</p>'            | '*Bold*, then\n\n'
            'inside a bold run'  | '<p><strong><em>both</em></strong></p>'         | '*_both_*\n\n'
    }

    def 'the unconstrained form is used where the constrained one would not be read: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                     | html                                          | expected
            'after a closing brace'  | '<p>a}<strong>b</strong> c</p>'               | 'a}**b** c\n\n'
            'after an ampersand'     | '<p>a&amp;<strong>b</strong> c</p>'           | 'a&**b** c\n\n'
            'after a non-ASCII letter' | '<p>caf&eacute;<strong>bar</strong> c</p>'   | 'café**bar** c\n\n'
            'before the closing _'   | '<p><em>a<strong>b</strong></em></p>'         | '_a **b**_\n\n'
            'inside an emphasis run' | '<p><em><strong>b</strong></em></p>'          | '_**b**_\n\n'
    }

    def 'subscript and superscript never double their character, having only one form'() {
        expect:
            // Their unconstrained form is the only one, which is what lets H~2~O work mid-word.
            Html2Adoc.convert(html) == expected

        where:
            html                                  | expected
            '<p>a <sub>2</sub> b</p>'             | 'a ~2~ b\n\n'
            '<p>caf&eacute;<sub>2</sub> x</p>'    | 'café~2~ x\n\n'
    }

    def 'an element with nothing in it writes nothing'() {
        expect:
            // Two markers with nothing between them are not an empty span, they open an
            // unconstrained one that never closes and swallow the rest of the paragraph.
            Html2Adoc.convert('<p><strong></strong>nothing</p>') == 'nothing\n\n'
    }
}
