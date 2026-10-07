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
