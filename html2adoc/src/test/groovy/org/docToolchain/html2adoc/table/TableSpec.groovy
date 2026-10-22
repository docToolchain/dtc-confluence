/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.table

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/** The upstream TableTest. */
class TableSpec extends Specification {

    @TempDir
    Path directory

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

    def 'an unevenly divided table keeps the widths it declares: #name'() {
        given: '''A <colgroup> is how HTML says a table is not evenly divided, and cols="N*" says
                  the opposite: a 30/70 table came out 50/50. Equal widths are left to "N*",
                  which already states them.'''
        expect:
            Html2Adoc.convert(html).readLines().first() == expected

        where:
            name                | html                                                            | expected
            'style, per cent'   | table('<col style="width: 30%;"/><col style="width: 70%;"/>')   | '[cols="30%,70%"]'
            'pixels, attribute' | table('<col width="110"/><col width="220"/>')                   | '[cols="110,220"]'
            'width attribute'   | table('<col width="25%"/><col width="75%"/>')                   | '[cols="25%,75%"]'
            'equal, left to N*' | table('<col style="width: 50%;"/><col style="width: 50%;"/>')   | '[cols="2*"]'
            'one column short'  | table('<col style="width: 30%;"/>')                             | '[cols="2*"]'
            'no width declared' | table('<col/><col/>')                                           | '[cols="2*"]'
            'no colgroup'       | table('')                                                       | '[cols="2*"]'
            'a pixel width'     | table('<col style="width: 30px"/><col style="width: 70px"/>')   | '[cols="2*"]'
            'upper case WIDTH'  | table('<col style="WIDTH: 30%"/><col style="WIDTH: 70%"/>')     | '[cols="30%,70%"]'
            'style beats width' | table('<col width="80%" style="width: 20%"/>' +
                                        '<col width="20%" style="width: 80%"/>')                   | '[cols="20%,80%"]'
            'a span covers two' | '<table><colgroup><col style="width: 20%"/>' +
                                  '<col span="2" style="width: 40%"/></colgroup><tbody><tr>' +
                                  '<td>a</td><td>b</td><td>c</td></tr></tbody></table>'            | '[cols="20%,40%,40%"]'
    }

    def 'a width AsciiDoc cannot read is rounded until it can: #name'() {
        given: '''AsciiDoc's colspec takes integers only, and one decimal makes the whole
                  specification unparsable - asciidoctor then counts the columns from the first
                  line of the table, which this converter writes one cell at a time, so the table
                  collapsed to a single column with every cell on a row of its own. 33.3333% is
                  not exotic: it is asciidoctor's own HTML for cols="1,2".'''
        expect:
            Html2Adoc.convert(html).readLines().first() == expected

        where:
            name                | html                                                                    | expected
            'a third and a half'| table('<col style="width: 33.3333%"/><col style="width: 66.6667%"/>')   | '[cols="33%,67%"]'
            'equal, decimal'    | table('<col style="width: 50.0%"/><col style="width: 50%"/>')           | '[cols="2*"]'
            'rounded apart'     | table('<col style="width: 30.4%"/><col style="width: 69.6%"/>')         | '[cols="30%,70%"]'
    }

    @Requires({ TableSpec.asciidoctorIsInstalled() })
    def 'a table with declared widths is still the table it was'() {
        given: 'the shape this converter writes, one cell to a line, which is where it collapsed'
            def adoc = Html2Adoc.convert(
                    '<table><colgroup><col style="width: 33.3333%"/><col style="width: 66.6667%"/>' +
                    '</colgroup><thead><tr><th>A</th><th>B</th></tr></thead>' +
                    '<tbody><tr><td>1</td><td>2</td></tr></tbody></table>')

        when:
            def rendered = render(adoc)

        then: 'two columns, two rows - not one column with a row per cell'
            rendered.count('<col ') == 2
            rendered.count('<tr>') == 2

        and: 'and the ratio the HTML declared'
            rendered.contains('width: 33%')
            rendered.contains('width: 67%')
    }

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    private String render(String adoc) {
        Path source = directory.resolve('table.adoc')
        Files.writeString(source, adoc)
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }

    /** @return a two-column table whose colgroup is {@code columns} */
    private static String table(String columns) {
        return '<table>' + (columns.isEmpty() ? '' : '<colgroup>' + columns + '</colgroup>') +
                '<tbody><tr><td>a</td><td>b</td></tr></tbody></table>'
    }
}
