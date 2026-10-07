/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.listing

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * The upstream ListingTest. Its expected output held raw {@code 0xA0} bytes in an ISO-8859-1 source
 * file; here the non-breaking space is spelled out, so the case no longer depends on the encoding
 * the file happens to be saved in.
 */
class ListingSpec extends Specification {

    private static final String NBSP = ' '

    def 'a two-row table captioned as an example becomes a listing block'() {
        given:
            def html = '    <table border="1" cellpadding="4" cellspacing="0" width="100%" align="center">' +
                    '    <tbody>' +
                    '     <tr bgcolor="#A6A5C2">' +
                    '      <td>Exemple :</td>' +
                    '     </tr>' +
                    '     <tr>' +
                    '      <td> <pre><code>package com.example.abc;\n' +
                    '\n' +
                    'public class Test {\n' +
                    '\n' +
                    '&nbsp; public static void main(String[] args) {\n' +
                    '&nbsp;&nbsp;&nbsp; System.out.println(&quot;test&quot;);\n' +
                    '&nbsp; }\n' +
                    '}</code></pre> </td>' +
                    '     </tr>' +
                    '    </tbody>' +
                    '   </table>'

        expect:
            Html2Adoc.convert(html) == '\n' +
                    '----\n' +
                    'package com.example.abc;\n' +
                    '\n' +
                    'public class Test {\n' +
                    '\n' +
                    NBSP + ' public static void main(String[] args) {\n' +
                    NBSP + NBSP + NBSP + ' System.out.println("test");\n' +
                    NBSP + ' }\n' +
                    '}\n' +
                    '----\n\n'
    }

    def 'a table without that caption stays a table'() {
        given:
            def html = '<table><tbody>' +
                    '<tr><td>Beispiel:</td></tr>' +
                    '<tr><td><pre><code>x = 1</code></pre></td></tr>' +
                    '</tbody></table>'

        expect:
            Html2Adoc.convert(html).startsWith('[cols="1*"]')
    }
}
