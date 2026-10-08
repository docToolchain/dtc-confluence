/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.block

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * A block whose input is preceded by text, which is what an element no converter claims leaves
 * behind, and what a macro body written as bare text is.
 *
 * Markup that opens a block is only markup at the start of a line with a blank line above it.
 * Written where the previous text ended, it is read as part of that text and the block goes with
 * it: a heading becomes a word of a paragraph, and a table's `[cols]` line becomes words of one.
 * Each case is therefore checked twice, once against the AsciiDoc and once against what
 * asciidoctor makes of it.
 */
class BlockStartSpec extends Specification {

    @TempDir
    Path directory

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def 'a block after text begins on a line of its own: #name'() {
        expect:
            Html2Adoc.convert(html) == expected

        where:
            name        | html                                        | expected
            'heading'   | 'lead<h2>Heading</h2>'                      | 'lead\n\n== Heading\n\n'
            'paragraph' | 'lead<p>Following.</p>'                     | 'lead\n\nFollowing.\n\n'
            'list'      | 'lead<ul><li>Item</li></ul>'                | 'lead\n\n* Item\n\n'
            'table'     | 'lead<table><tr><td>Cell</td></tr></table>' | 'lead\n\n[cols="1*"]\n|===\n\n|Cell\n|===\n'
            'rule'      | 'lead<hr/>'                                 | 'lead\n\n\'\'\'\n\n'
    }

    @Requires({ BlockStartSpec.asciidoctorIsInstalled() })
    def 'the block is still a block once rendered: #name'() {
        given:
            def rendered = render(Html2Adoc.convert(html))

        expect: 'the element the block renders as is there'
            rendered.contains(element)

        and: 'and the text in front of it is a paragraph of its own, having swallowed nothing'
            rendered.contains('<p>lead</p>')

        where:
            name        | html                                                   | element
            'heading'   | 'lead<h2>Heading</h2>'                                 | '<h2 id="_heading">Heading</h2>'
            'paragraph' | 'lead<p>Following.</p>'                                | '<p>Following.</p>'
            'list'      | 'lead<ul><li>Item</li></ul>'                           | '<div class="ulist">'
            'table'     | 'lead<table><tr><td>Cell</td></tr></table>'            | '<table class='
            'header'    | 'lead<table><thead><tr><th>Key</th></tr></thead>' +
                          '<tbody><tr><td>a</td></tr></tbody></table>'           | '<th class='
    }

    private String render(String adoc) {
        Path source = directory.resolve('block.adoc')
        Files.writeString(source, adoc)
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }
}
