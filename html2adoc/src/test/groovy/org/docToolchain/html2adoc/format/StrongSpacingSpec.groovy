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

    def 'punctuation before a formatted run keeps the space after it: #name'() {
        given: '''The space is in the HTML, and the words on the page are not the same without it.
                  It used to be derived from the last character of the text instead, through a
                  class that lists a full stop and a colon but neither a comma nor a semicolon.'''
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name            | html                                             | expected
            'comma'         | '<p>Lorem, <strong>ipsum</strong> dolor</p>'     | 'Lorem, *ipsum* dolor\n\n'
            'semicolon'     | '<p>Lorem; <strong>ipsum</strong> dolor</p>'     | 'Lorem; *ipsum* dolor\n\n'
            'question mark' | '<p>Lorem? <strong>Ipsum</strong> dolor</p>'     | 'Lorem? *Ipsum* dolor\n\n'
            'closing quote' | '<p>"Lorem" <strong>ipsum</strong> dolor</p>'    | '"Lorem" *ipsum* dolor\n\n'
            'after a link'  | '<p>Lorem, <a href="x.html">ipsum</a> dolor</p>' | 'Lorem, <<x.adoc#,ipsum>> dolor\n\n'
    }

    def 'a space between two elements is a text node and still a space: #name'() {
        given: '''Confluence wraps almost everything in a span, so "</span> <strong>" is the usual
                  shape and the space between the two is a text node holding nothing else. Trimmed
                  to nothing it used to be passed over without a word, and the comma was written
                  against the span that followed it.'''
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                  | html                                                            | expected
            'span before strong'  | '<p><span class="x">Lorem,</span> <strong>ipsum</strong> d</p>' | 'Lorem, *ipsum* d\n\n'
            'em before strong'    | '<p><em>Lorem,</em> <strong>ipsum</strong></p>'                 | '_Lorem,_ *ipsum*\n\n'
            'element first'       | '<p><strong>a</strong> b</p>'                                   | '*a* b\n\n'
            'space before a comma'| '<p>a <strong>b</strong> , c</p>'                               | 'a *b* , c\n\n'
    }

    def 'a space that survives trimming is not answered with a second one: #name'() {
        given: '''These are whitespace to Character.isWhitespace, but String.trim leaves them and
                  jsoup does not collapse them, so the character is written out as part of the
                  text. Counted as a separator as well, it would be followed by a space of its
                  own and the words would stand two apart. A no-break space is not among them:
                  jsoup normalises it to an ordinary space before any of this, which is why
                  nothing here has to mention it.'''
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name                | html                                                | expected
            'en quad'           | '<p>Lorem,\u2000<strong>ipsum</strong></p>'         | 'Lorem,\u2000*ipsum*\n\n'
            'ideographic space' | '<p>Lorem,\u3000<strong>ipsum</strong></p>'         | 'Lorem,\u3000*ipsum*\n\n'
    }
}
