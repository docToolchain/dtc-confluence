/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.link

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream LinkTest, plus the localhost case the upstream code has and never tested. */
class LinkSpec extends Specification {

    def 'a link becomes the AsciiDoc form that matches where it points: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                     | html                                            | expected
            'external'               | '<a href="http://site.com/">this site</a>'      | 'link:http://site.com/[this site]'
            'same page anchor'       | '<a href="#anchor">this site</a>'               | '<<anchor,this site>>'
            'other page, htm'        | '<a href="file.htm#anchor">this site</a>'       | '<<file.adoc#anchor,this site>>'
            'other page, html'       | '<a href="file.html#anchor">this site</a>'      | '<<file.adoc#anchor,this site>>'
            'localhost, not a link'  | '<a href="http://localhost:8080/x">this site</a>' | '`this site`'
    }

    def 'an external link inside a paragraph keeps the words around it apart'() {
        expect:
            Html2Adoc.convert('<p>Lorem <a href="http://site.com/">this site</a> ipsum.</p>') ==
                    'Lorem link:http://site.com/[this site] ipsum.\n\n'
    }

    /**
     * The shapes the Confluence export writes, neither of which carries a fragment: a rewritten
     * link to a sibling page of the same export, and a link to an attachment. A target that is
     * dropped here is dropped from every exported page that links to a sibling or an attachment.
     */
    def 'a link without a fragment keeps its target: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                   | html                                               | expected
            'sibling page'         | '<a href="../Guide/Install.html">page</a>'         | '<<../Guide/Install.adoc#,page>>'
            'sibling page, htm'    | '<a href="Install.htm">page</a>'                   | '<<Install.adoc#,page>>'
            'attachment'           | '<a href="../images/P/1_spec.docx">spec</a>'       | 'link:../images/P/1_spec.docx[spec]'
            'extensionless path'   | '<a href="../other/page">page</a>'                 | 'link:../other/page[page]'
            'mail'                 | '<a href="mailto:a@b.example">write</a>'           | 'link:mailto:a@b.example[write]'
            'external html page'   | '<a href="http://site.com/a/b.html">page</a>'      | 'link:http://site.com/a/b.html[page]'
    }

    def 'an anchor is not a link, and only the words it wraps are kept'() {
        expect:
            Html2Adoc.convert('<p>Lorem <a name="anchor">ipsum</a> dolor.</p>') == 'Lorem ipsum dolor.\n\n'
    }
}
