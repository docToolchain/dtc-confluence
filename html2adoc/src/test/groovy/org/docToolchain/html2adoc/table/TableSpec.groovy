/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.table

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream TableTest. */
class TableSpec extends Specification {

    def 'a table becomes a cols attribute and one cell per line'() {
        given:
            def html = '<table width="80%" border="1" align="center" cellpadding="4" cellspacing="0">' +
                    '<tbody>' +
                    ' <tr>' +
                    '  <td>Date</td>' +
                    '  <td>Version</td>' +
                    ' </tr>' +
                    ' <tr>' +
                    '  <td>Septembre 2016</td>' +
                    '  <td>1.0</td>' +
                    ' </tr>' +
                    '</tbody>' +
                    '</table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Date\n' +
                    '|Version\n' +
                    '\n' +
                    '|Septembre 2016\n' +
                    '|1.0\n' +
                    '|===\n'
    }

    def 'a first row of th makes it a header row'() {
        given:
            def html = '<table cellspacing="0">\n' +
                    '  <tr><th width="110">Header 1</th><th>Header 2</th></tr>\n' +
                    '  <tr>\n' +
                    '    <td>Item 1</td>\n' +
                    '    <td>Item 2</td>\n' +
                    '  </tr>\n' +
                    '  <tr>\n' +
                    '    <td>Item a</td>\n' +
                    '    <td>Item b</td>\n' +
                    '  </tr>\n' +
                    '</table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*", options="header"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Header 1\n' +
                    '|Header 2\n' +
                    '\n' +
                    '|Item 1\n' +
                    '|Item 2\n' +
                    '\n' +
                    '|Item a\n' +
                    '|Item b\n' +
                    '|===\n'
    }
}
