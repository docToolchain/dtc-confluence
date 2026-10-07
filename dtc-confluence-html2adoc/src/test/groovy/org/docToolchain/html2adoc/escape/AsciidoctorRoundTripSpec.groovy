/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.escape

import org.docToolchain.html2adoc.Html2Adoc
import org.jsoup.Jsoup
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders what the converter wrote and checks that AsciiDoc gives the text back.
 *
 * The specs beside this one state what the output looks like, which is only half an answer: an
 * expectation can be wrong in exactly the way the code is wrong. This half asks asciidoctor. The
 * inputs are HTML with no formatting in it at all, so the answer is unambiguous - the words have to
 * come back unchanged, and no formatting may have appeared out of the punctuation.
 */
@Requires({ AsciidoctorRoundTripSpec.asciidoctorIsInstalled() })
class AsciidoctorRoundTripSpec extends Specification {

    @TempDir
    Path directory

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def 'text that looks like markup renders as the text it was: #html'() {
        given:
            def rendered = render(Html2Adoc.convert(html))

        expect: 'the words are the ones that went in'
            Jsoup.parse(rendered).text() == Jsoup.parse(html).text()

        and: 'and none of them turned into formatting on the way'
            !rendered.matches(/(?s).*<(strong|em|code|mark|sub|sup|a )[^>]*>.*/)

        where:
            html << [
                    '<p>a *bold* word</p>',
                    '<p>type _foo_ here</p>',
                    '<p>run `make` now</p>',
                    '<p>a #mark# b</p>',
                    '<p>a +pass+ b</p>',
                    '<p>x^2^ is four</p>',
                    '<p>H~2~O is water</p>',
                    '<p>a **bold** b</p>',
                    '<p>a __em__ b</p>',
                    '<p>use {nbsp} for a space</p>',
                    '<p>see [[anchor]] there</p>',
                    '<p>2 * 3 * 4 is 24</p>',
                    '<p>a_b_c stays</p>',
                    '<p>a ~ b ~ c</p>',
                    '<p>see [source, groovy] inline</p>',
                    '<p>end]] of it</p>',
                    '<p>-foo and *foo and =foo</p>',
                    '<p>.NET is a platform</p>',
                    '<p>- none -</p>',
                    '<p>= not a heading</p>',
                    '<p>// not a comment</p>',
                    '<p>:name: not an entry</p>',
                    '<p>----</p>',
                    '<p>[source, groovy]</p>',
            ]
    }

    def 'a formatted run renders as that formatting, whichever quoting form it needed: #html'() {
        given:
            def rendered = render(Html2Adoc.convert(html))

        expect:
            Jsoup.parse(rendered).text() == Jsoup.parse(html).text()

        and:
            rendered.contains(expectedMarkup)

        where:
            html                                        | expectedMarkup
            '<p>dtc-<strong>confluence</strong> x</p>'  | '<strong>confluence</strong>'
            '<p>a}<strong>b</strong> c</p>'             | '<strong>b</strong>'
            '<p>a&amp;<strong>b</strong> c</p>'         | '<strong>b</strong>'
            '<p>caf&eacute;<strong>bar</strong> c</p>'  | '<strong>bar</strong>'
            '<p><em>a <strong>b</strong></em></p>'      | '<em>a <strong>b</strong></em>'
            '<p>a <code>SELECT * FROM t</code> b</p>'   | '<code>SELECT * FROM t</code>'
            '<p>a <code>{nbsp}</code> b</p>'            | '<code>{nbsp}</code>'
    }

    def 'a cell separator in the text stays inside its cell'() {
        given:
            def html = '<table><tbody><tr><td>a | b</td><td>plain</td></tr></tbody></table>'

        when:
            def rendered = render(Html2Adoc.convert(html))

        then: 'two cells, and the pipe in the first one did not open a third'
            rendered.count('<td') == 2
            Jsoup.parse(rendered).select('td').first().text() == 'a | b'
    }

    /** The HTML asciidoctor makes of {@code adoc}, as an embedded document with no shell around it. */
    private String render(String adoc) {
        Path file = Files.createTempFile(directory, 'round-trip', '.adoc')
        Files.writeString(file, adoc)
        Process asciidoctor = new ProcessBuilder('asciidoctor', '-s', '-o', '-', file.toString())
                .redirectErrorStream(true)
                .start()
        String html = asciidoctor.inputStream.getText('UTF-8')
        assert asciidoctor.waitFor() == 0
        return html
    }
}
