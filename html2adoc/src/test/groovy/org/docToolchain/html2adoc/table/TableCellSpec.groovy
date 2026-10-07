/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.table

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * What goes inside a cell: blocks, nothing, and the character that would end the cell.
 *
 * <p>Every expectation here was rendered with {@code asciidoctor} and the shape of the HTML it
 * produced checked against the HTML that went in.</p>
 */
class TableCellSpec extends Specification {

    def 'two paragraphs in a cell make it an AsciiDoc block cell'() {
        given:
            def html = '<table><tbody><tr>' +
                    '<td><p>First.</p><p>Second.</p></td><td>plain</td>' +
                    '</tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    'a|\n' +
                    'First.\n' +
                    '\n' +
                    'Second.\n' +
                    '|plain\n' +
                    '|===\n'
    }

    def 'a list in a cell makes it a block cell, because a plain cell would print the bullets'() {
        given:
            def html = '<table><tbody><tr>' +
                    '<td><ul><li>one</li><li>two</li></ul></td><td>plain</td>' +
                    '</tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    'a|\n' +
                    '* one\n' +
                    '* two\n' +
                    '|plain\n' +
                    '|===\n'
    }

    def 'a table in a cell makes it a block cell, and the inner table switches to the bang separator'() {
        given:
            def html = '<table><tbody><tr>' +
                    '<td><table><tbody><tr><td>x</td><td>y</td></tr></tbody></table></td><td>plain</td>' +
                    '</tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    'a|\n' +
                    '[cols="2*"]\n' +
                    '!===\n' +
                    '\n' +
                    '!x\n' +
                    '!y\n' +
                    '!===\n' +
                    '|plain\n' +
                    '|===\n'
    }

    def 'the one paragraph Confluence wraps a cell in is not block content'() {
        given:
            def html = '<table><tbody><tr>' +
                    '<td><p class="tableblock">Cell 1</p></td>' +
                    '<td><p class="tableblock">Cell 2</p></td>' +
                    '</tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Cell 1\n' +
                    '|Cell 2\n' +
                    '|===\n'
    }

    def 'an empty cell keeps its separator, so that the row stays three cells wide'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td>a</td><td></td><td>c</td></tr>' +
                    '<tr><td>d</td><td>e</td><td>f</td></tr>' +
                    '</tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="3*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|a\n' +
                    '|\n' +
                    '|c\n' +
                    '\n' +
                    '|d\n' +
                    '|e\n' +
                    '|f\n' +
                    '|===\n'
    }

    def 'a pipe in the cell text is escaped, or it would end the cell early'() {
        given:
            def html = '<table><tbody><tr><td>a | b</td><td>c</td></tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|a \\| b\n' +
                    '|c\n' +
                    '|===\n'
    }

    def 'a bang in a top-level cell is left alone, being no separator of that table'() {
        given:
            def html = '<table><tbody><tr><td>a ! b</td><td>c</td></tr></tbody></table>'

        expect:
            // Only a nested table separates its cells with a bang. Escaping it here would show the
            // backslash on the page, which is the mistake the escaping is written to avoid.
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|a ! b\n' +
                    '|c\n' +
                    '|===\n'
    }

    def 'a nested cell escapes both separators, its own and the outer one'() {
        given:
            def html = '<table><tbody><tr>' +
                    '<td><table><tbody><tr><td>x | y</td><td>a ! b</td></tr></tbody></table></td>' +
                    '<td>plain</td>' +
                    '</tr></tbody></table>'

        expect:
            // The bang is escaped by the inner cell it belongs to, the pipe by the outer cell that
            // the whole inner table sits in: each cell escapes the separator of its own depth.
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    'a|\n' +
                    '[cols="2*"]\n' +
                    '!===\n' +
                    '\n' +
                    '!x \\| y\n' +
                    '!a \\! b\n' +
                    '!===\n' +
                    '|plain\n' +
                    '|===\n'
    }
}
