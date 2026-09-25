package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States what the placeholders become once pandoc has written its AsciiDoc.
 *
 * Pandoc escapes the punctuation AsciiDoc is built from, so anything that has to reach the
 * document as markup travels through it as a placeholder. What it is spelled as here is what the
 * exported document says, and there is no other test of that.
 */
class AdocOutputSpec extends Specification {

    def 'a source block gets its attribute line back'() {
        when: '''pandoc escapes a literal bracket in text: written as "[source, groovy]" the line
                 arrives as "++[++source, groovy++]++", which is not a source block but four plus
                 signs and a sentence'''
            def adoc = AdocOutput.substitute('%%SOURCE-BEGIN%%groovy%%SOURCE-END%%')

        then:
            adoc == '[source, groovy]'
    }

    def 'a source block without a language keeps its shape'() {
        expect:
            AdocOutput.substitute('%%SOURCE-BEGIN%%%%SOURCE-END%%') == '[source, ]'
    }

    def 'an admonition becomes a block, separated from what is above it'() {
        when:
            def adoc = AdocOutput.substitute('text%%ADMON-BEGIN-NOTE%%Worth knowing.%%ADMON-END%%')

        then:
            adoc == 'text\n\n[NOTE]\n====\n\nWorth knowing.\n\n====\n\n'
    }

    def 'the title of an admonition stays attached to its block'() {
        when: 'a blank line between the two would detach the title, and AsciiDoc would drop it'
            def adoc = AdocOutput.substitute(
                '%%ADMON-TITLE%% Mind this %%ADMON-TITLE-END%%%%ADMON-BEGIN-WARNING%%body')

        then:
            adoc.contains('.Mind this\n[WARNING]\n====')
    }

    def 'a collapsible block is delimited more deeply than an admonition'() {
        when: 'so that either may nest in the other'
            def adoc = AdocOutput.substitute(
                '%%EXPAND-TITLE%%Details%%EXPAND-TITLE-END%%%%EXPAND-BEGIN%%x%%EXPAND-END%%')

        then:
            adoc.contains('.Details\n[%collapsible]\n======\n')
            adoc.endsWith('\n\n======\n\n')
    }

    def 'an anchor is written as one AsciiDoc accepts, and stays with its block'() {
        when: 'an id must start with a letter or an underscore, and pandoc escapes underscores'
            def adoc = AdocOutput.substitute('before%%ANCHOR%%my++_++section%%ANCHOR-END%%heading')

        then:
            adoc == 'before\n\n[[_my_section]]\nheading'
    }

    def 'a table of row headers asks for a header column'() {
        when: 'and pandoc\'s own specifier is consumed - a table may carry only one'
            def adoc = AdocOutput.substitute('%%TABLE-ROWHEADER-3%%\n\n[cols=",,"]\n|===\n|a\n|===')

        then:
            adoc == '[cols="h,1,1"]\n|===\n|a\n|==='
    }

    def 'a status badge becomes an inline role with a stable name'() {
        expect: 'the colour lower case, so that a stylesheet can rely on it'
            AdocOutput.substitute('%%STATUS-BEGIN-Yellow%% wip %%STATUS-END%%') == '[.status.yellow]#wip#'
    }

    def 'an image on a line of its own becomes a block image'() {
        expect: 'pandoc writes the inline form even where the image stands alone'
            AdocOutput.substitute('image:pic.png[]') == 'image::pic.png[]'
    }

    def 'an image inside a sentence stays inline'() {
        expect:
            AdocOutput.substitute('see image:pic.png[] here') == 'see image:pic.png[] here'
    }

    def 'a placeholder line break becomes a real one'() {
        expect:
            AdocOutput.substitute('----%%CRLF%%   \ncode') == '----\n\ncode'
    }

    def 'a no-break space becomes the attribute that survives editing'() {
        expect: 'as a character it would be normalised to a space by the next editor to open it'
            AdocOutput.substitute("two\u00A0words") == 'two{nbsp}words'
    }

    def 'a heading marked discrete carries the attribute above it'() {
        expect:
            AdocOutput.substitute('%%DISCRETE%%== Title').contains('[discrete]\n== Title')
    }
}
