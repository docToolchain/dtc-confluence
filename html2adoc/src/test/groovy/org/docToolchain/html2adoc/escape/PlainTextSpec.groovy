/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.escape

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * Text that AsciiDoc leaves alone, and that the converter must therefore leave alone too.
 *
 * <p>This is the other half of {@link InlineMarkupSpec} and the more important one. An escape that
 * nothing needed is not untidy, it is wrong: AsciiDoc prints the backslash of a {@code \*} that
 * opens no span, so {@code 2 * 3} escaped on suspicion reaches the page as {@code 2 \* 3}. It is
 * the mistake pandoc makes in the other direction, and the reason the export carries placeholder
 * tokens past it.</p>
 */
class PlainTextSpec extends Specification {

    def 'a marker that opens no span keeps its backslash off: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                     | html                              | expected
            'spaces on both sides'   | '<p>2 * 3 * 4 is 24</p>'          | '2 * 3 * 4 is 24\n\n'
            'inside a word'          | '<p>a_b_c stays</p>'              | 'a_b_c stays\n\n'
            'subscript needs no gap' | '<p>a ~ b ~ c</p>'                | 'a ~ b ~ c\n\n'
            'no closing marker'      | '<p>a *lonely star</p>'           | 'a *lonely star\n\n'
            'closer runs into a word' | '<p>a *star*word b</p>'          | 'a *star*word b\n\n'
            'after a semicolon'      | '<p>a;*b* c</p>'                  | 'a;*b* c\n\n'
            'after an ampersand'     | '<p>a&amp;*b* c</p>'              | 'a&*b* c\n\n'
    }

    def 'a brace that is no attribute reference is left alone: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name            | html                          | expected
            'words inside'  | '<p>the set {a b} of</p>'     | 'the set {a b} of\n\n'
            'json'          | '<p>sends {"a":1} back</p>'   | 'sends {"a":1} back\n\n'
    }

    def 'brackets are only markup in the shapes that are markup: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                     | html                                       | expected
            'a block attribute line mid-sentence' | '<p>see [source, groovy] inline</p>' | 'see [source, groovy] inline\n\n'
            'two closing brackets'   | '<p>end]] of it</p>'                       | 'end]] of it\n\n'
            'one opening bracket'    | '<p>a [b] c</p>'                           | 'a [b] c\n\n'
    }

    def 'a block marker that is part of a word is not a block marker'() {
        expect:
            Html2Adoc.convert('<p>-foo and *foo and =foo</p>') == '-foo and *foo and =foo\n\n'
    }
}
