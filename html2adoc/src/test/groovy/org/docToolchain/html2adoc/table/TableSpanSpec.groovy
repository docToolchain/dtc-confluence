/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.table

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * A cell that covers more than one column or row.
 *
 * <p>Every expectation here was rendered with {@code asciidoctor} and the {@code colspan} and
 * {@code rowspan} of the HTML it produced checked against the HTML that went in.</p>
 */
class TableSpanSpec extends Specification {

    def 'a colspan becomes a cell specifier'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td colspan="2">Spanning two</td><td>Third</td></tr>' +
                    '<tr><td>a</td><td>b</td><td>c</td></tr>' +
                    '</tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="3*"]\n' +
                    '|===\n' +
                    '\n' +
                    '2+|Spanning two\n' +
                    '|Third\n' +
                    '\n' +
                    '|a\n' +
                    '|b\n' +
                    '|c\n' +
                    '|===\n'
    }

    def 'a rowspan becomes a cell specifier, and the row below stays one cell short'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td rowspan="2">Spanning two</td><td>first</td></tr>' +
                    '<tr><td>second</td></tr>' +
                    '</tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '.2+|Spanning two\n' +
                    '|first\n' +
                    '\n' +
                    '|second\n' +
                    '|===\n'
    }

    def 'both spans on one cell become one specifier, the columns before the rows'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td colspan="2" rowspan="2">Corner</td><td>r1c3</td></tr>' +
                    '<tr><td>r2c3</td></tr>' +
                    '<tr><td>a</td><td>b</td><td>c</td></tr>' +
                    '</tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="3*"]\n' +
                    '|===\n' +
                    '\n' +
                    '2.2+|Corner\n' +
                    '|r1c3\n' +
                    '\n' +
                    '|r2c3\n' +
                    '\n' +
                    '|a\n' +
                    '|b\n' +
                    '|c\n' +
                    '|===\n'
    }

    def 'the columns a colspan covers count towards the width of the table'() {
        given:
            def html = '<table><tbody><tr><td colspan="3">All three</td></tr></tbody></table>'

        expect:
            // One cell, three columns: counting cells would declare a table one column wide and
            // AsciiDoc would then narrow the cell to fit it.
            Html2Adoc.convert(html) == '[cols="3*"]\n' +
                    '|===\n' +
                    '\n' +
                    '3+|All three\n' +
                    '|===\n'
    }

    def 'a cell a rowspan carries into the next row counts towards the width of the table'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td rowspan="2">Tall</td></tr>' +
                    '<tr><td>beside it</td></tr>' +
                    '</tbody></table>'

        expect:
            // Neither row holds two cells, and the table is two columns wide all the same: the
            // second row's own cell cannot be in the column the first row's cell still occupies.
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '.2+|Tall\n' +
                    '\n' +
                    '|beside it\n' +
                    '|===\n'
    }

    def 'a span of one is no span, and writes no specifier'() {
        given:
            def html = '<table><tbody><tr><td colspan="1" rowspan="1">Plain</td><td>Also plain</td></tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Plain\n' +
                    '|Also plain\n' +
                    '|===\n'
    }
}
