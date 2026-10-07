/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.format

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/**
 * The upstream StrongTest, which is about where a space belongs around a formatted run rather than
 * about {@code <strong>}. HTML lets the space fall either side of the tag, or be missing; AsciiDoc
 * needs it outside the markers and nowhere else.
 */
class StrongSpacingSpec extends Specification {

    def 'strong keeps its surroundings intact: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                       | html                                                       | expected
            'whole paragraph'          | '<p><strong>This is important</strong>.</p>'               | '*This is important*.\n\n'
            'second sentence'          | '<p>Ipsum. <strong>This is important.</strong> Lorem.</p>' | 'Ipsum. *This is important.* Lorem.\n\n'
            'colon follows'            | '<p><strong>Lorem</strong>: ipsum</p>'                     | '*Lorem*: ipsum\n\n'
            'space missing in HTML'    | '<p>This is<strong>important</strong>!</p>'                | 'This is *important*!\n\n'
            'apostrophe before'        | "<p>Lorem d'<strong>ipsum</strong>?</p>"                   | "Lorem d'*ipsum*?\n\n"
            'after a numbered label'   | '<p>10. <strong>Ipsum</strong>;</p>'                       | '10. *Ipsum*;\n\n'
            'after a URL'              | '<p>http://www.xxx.yy/fr/ <strong>is</strong> correct!</p>' | 'http://www.xxx.yy/fr/ *is* correct!\n\n'
            'between numbers'          | '<p>10 <strong>is</strong> 10</p>'                         | '10 *is* 10\n\n'
            'between slashed words'    | '<p>/xxx/ <strong>is</strong> /xxx/</p>'                    | '/xxx/ *is* /xxx/\n\n'
            'ellipsis inside'          | '<p>Lorem: <strong>Ipsum...</strong></p>'                  | 'Lorem: *Ipsum...*\n\n'
            'between brackets'         | '<p>(lorem) <strong>is</strong> (ipsum)</p>'               | '(lorem) *is* (ipsum)\n\n'
    }
}
