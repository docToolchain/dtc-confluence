/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.rule

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** {@code <hr>} occurs four times in the corpus, each time above a block of footnotes. */
class HorizontalRuleSpec extends Specification {

    def 'a horizontal rule becomes a thematic break'() {
        expect:
            Html2Adoc.convert('<p>Above</p><hr /><p>Below</p>') == 'Above\n\n' +
                    '\'\'\'\n' +
                    '\n' +
                    'Below\n\n'
    }

    def 'a thematic break opens its own line, even where the line was left open'() {
        given:
            // The corpus shape: the footnotes of a page, following an image block. The image comes
            // out inline and leaves the line open - that is a known gap of ImageConverter, and the
            // trailing space below is it; the rule still has to start a line of its own.
            def html = '<div class="imageblock"><div class="content"><img src="logo.png" /></div></div>' +
                    '<div class="footnotes"><hr /><p>A note</p></div>'

        expect:
            Html2Adoc.convert(html) == ' image:logo.png[] \n' +
                    '\n' +
                    '\'\'\'\n' +
                    '\n' +
                    'A note\n\n'
    }
}
