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

    def 'a pre holding text of its own carries no delimiters'() {
        expect: 'the HTML does not say which block that is, so whoever wrote it owns the delimiters'
            Html2Adoc.convert('<pre>code</pre>') == 'code'
    }

    def 'a pre holding one code element is a listing block'() {
        expect: 'the shape a code macro arrives in, and the shape pandoc reads'
            Html2Adoc.convert('<pre><code class="language-groovy">def hello = "world"</code></pre>') ==
                    '[source, groovy]\n----\ndef hello = "world"\n----\n'

        and: 'the sample is the block, not a monospace span inside it'
            !Html2Adoc.convert('<pre><code>plain</code></pre>').contains('`')

        and: 'and without a language it is still a listing, just unnamed'
            Html2Adoc.convert('<pre><code>plain</code></pre>') == '----\nplain\n----\n'
    }

    def 'a listing sample keeps the characters it was written with'() {
        expect: 'the escaping is off inside the delimiters, the reason this converter exists'
            Html2Adoc.convert('<pre><code>cp *.txt ${HOME}/out</code></pre>')
                    .contains('cp *.txt ${HOME}/out')
    }
}
