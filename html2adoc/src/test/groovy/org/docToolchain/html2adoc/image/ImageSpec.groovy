/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.image

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Specification

/** The upstream ImageTest. */
class ImageSpec extends Specification {

    private static final String NBSP = ' '

    def 'an image alone in its paragraph becomes a block image: #name'() {
        expect:
            Html2Adoc.convert(html) == '\nimage::images/install002.png[]\n\n\n'

        where:
            name            | html
            'plain'         | '<p><img src="images/install002.png" width="360" height="144" /></p>'
            'centred'       | '<p align="center"><img src="images/install002.png" width="360" height="144" /></p>'
            'trailing space'| '<p><img src="images/install002.png" width="360" height="144" /> </p>'
    }

    def 'an image that opens a line is not indented'() {
        given: '''A Confluence image block, which reaches the converter wrapped in divs: the
                  wrappers are transparent, so the image stands at the start of the line, and the
                  space that separates an inline image from the words before it has nothing to
                  separate it from. Indented, the line is a literal block and the image is printed
                  as markup rather than shown.'''
        expect:
            !Html2Adoc.convert(html).readLines().any { it.startsWith(' image:') }

        and: 'and the line is the image, from its first character'
            Html2Adoc.convert(html).readLines().grep { it.contains('image:') }
                    .every { it.startsWith('image:') }

        where:
            name                | html
            'a bare wrapper'    | '<div><img src="images/install002.png" /></div>'
            'a Confluence block'| '<div class="imageblock"><div class="content">' +
                                    '<img src="images/install002.png" /></div></div>'
    }

    def 'an image directly in the body becomes a block image'() {
        expect:
            Html2Adoc.convert('<body><img src="images/install002.png" width="360" height="144" /></body>') ==
                    '\nimage::images/install002.png[]\n\n'
    }

    def 'a non-breaking space beside the image leaves no trace'() {
        expect:
            Html2Adoc.convert('<p><img src="images/install002.png" width="360" height="144" />&nbsp;</p>') ==
                    '\nimage::images/install002.png[]\n\n\n'
    }

    def 'two images in one paragraph are both inline'() {
        expect:
            Html2Adoc.convert('<p align="center"><img src="images/install001.png" /><img src="images/install002.png" /></p>') ==
                    ' image:images/install001.png[]  image:images/install002.png[] \n\n'
    }

    def 'an image between words is inline'() {
        expect:
            Html2Adoc.convert('<p>Lorem <img src="images/install002.png"/> Ipsum</p>') ==
                    'Lorem image:images/install002.png[] Ipsum\n\n'
    }

    def 'an image in a table cell is inline, because a block image would break the cell'() {
        given:
            def html = '<p><table><tr><td>Lorem</td><td><p align="center">' +
                    '<img src="images/install002.png"/></p></td><td>Ipsum</td></tr></table></p>'

        expect:
            Html2Adoc.convert(html) == '[cols="3*"]\n' +
                    '|===\n' +
                    '\n' +
                    '|Lorem\n' +
                    '| image:images/install002.png[] \n' +
                    '|Ipsum\n' +
                    '|===\n\n\n'
    }
}
