/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.escape

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * Text that AsciiDoc would read as inline markup, and has to be escaped to stay text.
 *
 * <p>Every case here is one where the unescaped output is wrong and not merely ugly: AsciiDoc would
 * render the characters as formatting, or swallow them, or replace them with something else. The
 * cases where it would not are in {@link PlainTextSpec}, and what both halves render as is checked
 * against asciidoctor in {@link AsciidoctorRoundTripSpec}.</p>
 */
class InlineMarkupSpec extends Specification {

    def 'a formatting span in the text is escaped: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                   | html                             | expected
            'bold'                 | '<p>a *bold* word</p>'           | 'a \\*bold* word\n\n'
            'emphasis'             | '<p>type _foo_ here</p>'         | 'type \\_foo_ here\n\n'
            'monospace'            | '<p>run `make` now</p>'          | 'run \\`make` now\n\n'
            'mark'                 | '<p>a #mark# b</p>'              | 'a \\#mark# b\n\n'
            'passthrough'          | '<p>a +pass+ b</p>'              | 'a \\+pass+ b\n\n'
            'superscript mid-word' | '<p>x^2^ is four</p>'            | 'x\\^2^ is four\n\n'
            'subscript mid-word'   | '<p>H~2~O is water</p>'          | 'H\\~2~O is water\n\n'
    }

    def 'the unconstrained form needs its markers held apart: #name'() {
        expect:
            // A backslash in front of the first marker alone would leave the second one against it,
            // and the two would open the span after all. The attribute between them resolves to
            // nothing, so the page shows the markers and no span.
            Html2Adoc.convert(html) == expected

        where:
            name            | html                      | expected
            'double star'   | '<p>a **bold** b</p>'     | 'a \\*{empty}*bold** b\n\n'
            'double score'  | '<p>a __em__ b</p>'       | 'a \\_{empty}_em__ b\n\n'
            'double tick'   | '<p>a ``code`` b</p>'     | 'a \\`{empty}`code`` b\n\n'
    }

    def 'an attribute reference is escaped, because AsciiDoc would resolve it: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name            | html                                | expected
            'built in'      | '<p>use {nbsp} for a space</p>'     | 'use \\{nbsp} for a space\n\n'
            'counter'       | '<p>set {counter:n} here</p>'       | 'set \\{counter:n} here\n\n'
            'inside code'   | '<p>a <code>{nbsp}</code> b</p>'    | 'a `\\{nbsp}` b\n\n'
    }

    def 'an inline anchor is escaped, because AsciiDoc would take it off the page'() {
        expect:
            Html2Adoc.convert('<p>see [[anchor]] there</p>') == 'see \\[[anchor]] there\n\n'
    }
}
