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

    def 'a listing block is left alone by every substitution'() {
        given: '''AsciiDoc substitutes nothing inside a listing block, so neither may this. A code
                  sample is the one place where text that looks like markup is not markup.'''
            def adoc = '''[source,yaml]
----
services:
  web:
    image: nginx:1.25
    command: sh -c "echo done"
----
'''

        expect: 'the docker-compose line keeps its single colon'
            AdocOutput.substitute(adoc) == adoc
    }

    def 'a line that only looks like a placeholder is not substituted inside a listing'() {
        given: 'a sample about this very conversion, which a page of ours could well contain'
            def adoc = '''[source,text]
----
%%ANCHOR%%name%%ANCHOR-END%%
%%CRLF%%
----
'''

        expect:
            AdocOutput.substitute(adoc) == adoc
    }

    def 'a literal block is left alone as well'() {
        given: """pandoc writes "...." rather than "----" for a sample whose language Confluence
                  did not record, and a language-less sample is no less verbatim"""
            def adoc = '''....
services:
  web:
    image: nginx:1.25
....
'''

        expect:
            AdocOutput.substitute(adoc) == adoc
    }

    def 'outside a listing block the substitutions still run'() {
        expect: 'the guard must not switch the conversion off for the rest of the document'
            AdocOutput.substitute('image:pic.png[]') == 'image::pic.png[]'
            AdocOutput.substitute('a%%CRLF%%b') == 'a\nb'
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
