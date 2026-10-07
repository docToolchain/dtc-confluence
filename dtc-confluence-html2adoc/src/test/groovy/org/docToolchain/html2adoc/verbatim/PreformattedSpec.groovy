/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.verbatim

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * Text inside a `<pre>` reaches the output unescaped.
 *
 * An escape in a verbatim block is not merely unnecessary, it is visible: AsciiDoc substitutes
 * nothing between the delimiters of a literal or listing block and strips no backslash there, so
 * every backslash the escaping inserted is printed as part of the code sample. The same strings
 * outside a `<pre>` are escaped, which is what the pairs below check in the same breath.
 */
class PreformattedSpec extends Specification {

    def 'a code sample keeps the characters it was written with: #name'() {
        expect:
            Html2Adoc.convert("<pre>${sample}</pre>") == sample

        and: 'and the same text as prose does need the escape, which is why it is not escaped here'
            Html2Adoc.convert("<p>${sample}</p>") != sample + '\n\n'

        where:
            name                  | sample
            'attribute reference' | 'cp *.txt ${HOME}/out'
            'a set: expression'   | 'print {set:x}'
            'subscript markers'   | 'String s = "H~2~O";'
            'superscript markers' | 'int p = x^2^;'
            'an inline anchor'    | 'label[[id]]end'
    }

    def 'a wrapper does not make the text around it verbatim'() {
        expect:
            Html2Adoc.convert('<p>before <pre>${HOME}</pre> after ${HOME}</p>')
                    .contains('after $\\{HOME}')
    }

    def 'a code sample does not carry block delimiters of its own'() {
        expect: 'whoever wrote the pre owns the delimiters; the corpus writes them around it'
            Html2Adoc.convert('<pre>code</pre>') == 'code'
    }
}
