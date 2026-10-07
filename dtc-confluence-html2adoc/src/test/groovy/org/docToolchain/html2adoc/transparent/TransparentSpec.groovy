/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.transparent

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * A wrapper element is dropped and its children are kept. What distinguishes that from letting the
 * unknown-element fallback drop it is the space: the fallback owes one to the next text, a wrapper
 * does not, so each case below wraps the second half of a single word.
 */
class TransparentSpec extends Specification {

    def 'a div is dropped without separating the words it wraps'() {
        expect:
            Html2Adoc.convert('<ul><li>dtc-<div>confluence</div></li></ul>') == '* dtc-confluence\n\n'
    }

    def 'a span is dropped without separating the words it wraps'() {
        expect:
            Html2Adoc.convert('<p>dtc-<span>confluence</span></p>') == 'dtc-confluence\n\n'
    }

    def 'a wrapping div keeps the blocks inside it as blocks'() {
        expect:
            Html2Adoc.convert('<div class="sectionbody"><p>One</p><p>Two</p></div>') == 'One\n\nTwo\n\n'
    }
}
