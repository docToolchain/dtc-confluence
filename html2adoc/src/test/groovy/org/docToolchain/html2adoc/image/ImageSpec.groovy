/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.image

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Requires
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/** The upstream ImageTest. */
class ImageSpec extends Specification {

    @TempDir
    Path directory

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    private static final String NBSP = ' '

    def 'an image alone in its paragraph becomes a block image: #name'() {
        expect:
            Html2Adoc.convert(html) == '\nimage::images/install002.png[width="360",height="144"]\n\n\n'

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
                    '\nimage::images/install002.png[width="360",height="144"]\n\n'
    }

    def 'a non-breaking space beside the image leaves no trace'() {
        expect:
            Html2Adoc.convert('<p><img src="images/install002.png" width="360" height="144" />&nbsp;</p>') ==
                    '\nimage::images/install002.png[width="360",height="144"]\n\n\n'
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

    def 'the attributes an author set reach the image: #name'() {
        given: '''Upstream read only the src and wrote an empty attribute list, so the size and
                  the alignment were dropped and a 64-pixel icon came out at its full size.
                  Written by name, because the positional order is alt, width, height - a width
                  without an alt in front of it would be read as the alt text.'''
        expect:
            Html2Adoc.convert(html).trim() == expected

        where:
            name            | html                                                          | expected
            'width'         | '<p><img src="i.png" width="64"/></p>'                        | 'image::i.png[width="64"]'
            'width, height' | '<p><img src="i.png" width="64" height="32"/></p>'            | 'image::i.png[width="64",height="32"]'
            'alignment'     | '<p><img src="i.png" align="center"/></p>'                    | 'image::i.png[align="center"]'
            'alt text'      | '<p><img src="i.png" alt="A chart"/></p>'                     | 'image::i.png[alt="A chart"]'
            'the corpus img'| '<p><img src="i.png" align="center" width="64"/></p>'         | 'image::i.png[width="64",align="center"]'
            // Passes without the change too; kept as the guard that an image with no attributes
            // still writes the empty list rather than none.
            'nothing set'   | '<p><img src="i.png"/></p>'                                   | 'image::i.png[]'
            'empty value'   | '<p><img src="i.png" alt="" width="64"/></p>'                 | 'image::i.png[width="64"]'
    }

    @Requires({ ImageSpec.asciidoctorIsInstalled() })
    def 'a value keeps what AsciiDoc would otherwise read as markup: #name'() {
        given: '''A quotation mark ends the value, an attribute reference is resolved when the
                  page renders - a counter is even incremented - and a "]" ends an inline macro's
                  list. All three have an escape, so the author's text is kept rather than
                  stripped. Asserted on the rendered alt, because that is where it has to arrive.'''
        expect:
            altOf(render(Html2Adoc.convert(html))) == alt

        where:
            name                  | html                                                      | alt
            'a quotation mark'    | '<p><img src="i.png" alt=\'He said "no", loudly\'/></p>'  | 'He said "no", loudly'
            'an attribute name'   | '<p><img src="i.png" alt="count {counter:q} up"/></p>'    | 'count {counter:q} up'
            'a brace that is not' | '<p><img src="i.png" alt="a {b c} d"/></p>'               | 'a {b c} d'
            'a closing bracket'   | '<p>x<img src="i.png" alt="a ] b"/>y</p>'                 | 'a ] b'
            'a newline'           | '<p><img src="i.png" alt="line one\nline two"/></p>'      | 'line one line two'
    }

    def 'an alignment is written only where AsciiDoc has one, and only as it reads it: #name'() {
        expect:
            Html2Adoc.convert(html).contains(expected)

        where:
            name                  | html                                                           | expected
            'lower case'          | '<p><img src="i.png" align="center"/></p>'                     | 'align="center"'
            'upper case'          | '<p><img src="i.png" align="CENTER"/></p>'                     | 'align="center"'
            'not an alignment'    | '<p><img src="i.png" align="middle" width="64"/></p>'          | 'i.png[width="64"]'
            'inline has none'     | '<table><tr><td><img src="i.png" align="center" width="64"/></td></tr></table>' | 'i.png[width="64"]'
    }

    private static String altOf(String rendered) {
        def matcher = rendered =~ /alt="([^"]*)"/
        assert matcher.find()
        return matcher.group(1).replace('&quot;', '"').replace('&amp;', '&')
    }

    private String render(String adoc) {
        Path source = directory.resolve('image.adoc')
        Files.writeString(source, adoc)
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }
}
