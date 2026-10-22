/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.table

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * The table shape of the corpus: a {@code colgroup} of relative widths, a {@code thead} and a
 * {@code tbody}. The header row has to land inside the table and be marked as a header.
 */
class TableHeadSpec extends Specification {

    def 'a thead row becomes the header row of the table'() {
        given:
            def html = '<table class="tableblock frame-all grid-all stretch">\n' +
                    '<colgroup>\n' +
                    '<col style="width: 50%;" />\n' +
                    '<col style="width: 50%;" />\n' +
                    '</colgroup>\n' +
                    '<thead>\n' +
                    '<tr>\n' +
                    '<th class="tableblock halign-left valign-top">Column A</th>\n' +
                    '<th class="tableblock halign-left valign-top">Column B</th>\n' +
                    '</tr>\n' +
                    '</thead>\n' +
                    '<tbody>\n' +
                    '<tr>\n' +
                    '<td class="tableblock halign-left valign-top">Cell 1</td>\n' +
                    '<td class="tableblock halign-left valign-top">Cell 2</td>\n' +
                    '</tr>\n' +
                    '</tbody>\n' +
                    '</table>'

        expect:
            Html2Adoc.convert(html) == '[cols="2*", options="header"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Column A\n' +
                    '|Column B\n' +
                    '\n' +
                    '|Cell 1\n' +
                    '|Cell 2\n' +
                    '|===\n'
    }

    def 'a thead of td is a header row all the same'() {
        given:
            def html = '<table><thead><tr><td>Column A</td></tr></thead>' +
                    '<tbody><tr><td>Cell 1</td></tr></tbody></table>'

        expect:
            Html2Adoc.convert(html) == '[cols="1*", options="header"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Column A\n' +
                    '\n' +
                    '|Cell 1\n' +
                    '|===\n'
    }
}
