/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.list

import org.docToolchain.html2adoc.Html2Adoc
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/** The upstream ListTest. */
class ListSpec extends Specification {

    @TempDir
    Path directory

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def 'an unordered list becomes asterisks'() {
        expect:
            Html2Adoc.convert('<ul><li>lorem</li><li>ipsum</li></ul>') == '* lorem\n* ipsum\n\n'
    }

    def 'an ordered list becomes dots'() {
        expect:
            Html2Adoc.convert('<ol><li>lorem</li><li>ipsum</li></ol>') == '. lorem\n. ipsum\n\n'
    }

    def 'a line break in the list markup is ignored: #name'() {
        expect:
            Html2Adoc.convert(html) == '* lorem\n* ipsum\n\n'

        where:
            name                   | html
            'between ul and li'    | '<ul><br><li>lorem</li><li>ipsum</li></ul>'
            'inside the first li'  | '<ul><li><br>lorem</li><li>ipsum</li></ul>'
    }

    def 'punctuation at the start of an item gains no space after the bullet'() {
        expect:
            Html2Adoc.convert('<ul><li>"lorem"</li><li>...</li></ul>') == '* "lorem"\n* ...\n\n'
    }

    def 'a list inside a list item is a list inside it: #name'() {
        given: '''ListConverter recorded which kind of list an item was in and not how deep it
                  stood, so a <ul> inside an <li> wrote "*" like its parent and its items became
                  items of the list around them: three items in two lists came out as three in
                  one, and the nesting was gone from the page.'''
        expect:
            Html2Adoc.convert(html).readLines().findAll { it =~ /^[*.]/ } == expected

        where:
            name              | html                                                                  | expected
            'the corpus shape'| '<ul><li>First</li><li>Second<ul><li>nested</li></ul></li></ul>'      | ['* First', '* Second', '** nested']
            'three deep'      | '<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li></ul>'           | ['* a', '** b', '*** c']
            'ordered inside'  | '<ul><li>a<ol><li>b</li></ol></li></ul>'                              | ['* a', '.. b']
            'unordered inside'| '<ol><li>a<ul><li>b</li></ul></li></ol>'                              | ['. a', '** b']
            // Not evidence for the depth: a converter returns the context it was handed, so the
            // depth never leaked between siblings. Kept as the guard that it stays that way.
            'two sibling lists'| '<ul><li>a</li></ul><ul><li>b</li></ul>'                             | ['* a', '* b']
    }

    @Requires({ ListSpec.asciidoctorIsInstalled() })
    def 'an item that holds only a nested list keeps every item on the page'() {
        given: '''A <li> wrapping nothing but a <ul> writes "* " with nothing after it, which is
                  not a list item to AsciiDoc but the start of a paragraph. The blank line every
                  list is written after is what stops the markers that follow being swallowed into
                  that paragraph as bold - without it the first nested item left the page and the
                  rest of the hierarchy inverted. Hence this is rendered, not matched.'''
        when:
            def rendered = render(Html2Adoc.convert(
                    '<ul><li><ul><li><p>a</p></li><li><p>b</p></li></ul></li><li><p>c</p></li></ul>'))

        then: 'four items: the empty one, the two it holds, and the one beside it'
            rendered.count('<li>') == 4

        and: 'in the two lists the HTML has, rather than one list or a paragraph'
            rendered.count('<div class="ulist">') == 2
            !rendered.contains('<div class="paragraph">')
    }

    def 'an item with no content at all is still an item'() {
        expect:
            Html2Adoc.convert('<ul><li></li><li>b</li></ul>').readLines()
                    .findAll { it.startsWith('*') } == ['* {empty}', '* b']
    }

    private String render(String adoc) {
        Path source = directory.resolve('list.adoc')
        Files.writeString(source, adoc)
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }
}
