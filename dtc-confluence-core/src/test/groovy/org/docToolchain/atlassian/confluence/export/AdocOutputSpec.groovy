package org.docToolchain.atlassian.confluence.export

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * States what the placeholders become once pandoc has written its AsciiDoc.
 *
 * Pandoc escapes the punctuation AsciiDoc is built from, so anything that has to reach the
 * document as markup travels through it as a placeholder. What it is spelled as here is what the
 * exported document says, and there is no other test of that.
 */
class AdocOutputSpec extends Specification {

    @TempDir
    Path directory

    def 'an emoticon becomes an attribute reference with a default beside it'() {
        when:
            def adoc = AdocOutput.substitute('A cell says %%EMOTICON-tick%% here.\n')

        then: 'the reference, so that one -a emoticon-tick=... changes every one of them'
            adoc.contains('A cell says {emoticon-tick} here.')

        and: 'and a default, guarded so that a value from outside still wins'
            adoc.contains('ifndef::emoticon-tick[:emoticon-tick: ✓]')

        and: 'written in front of the content that needs it'
            adoc.indexOf('ifndef::emoticon-tick') < adoc.indexOf('{emoticon-tick}')
    }

    def 'only the emoticons a page uses are defined on it'() {
        when:
            def adoc = AdocOutput.substitute('%%EMOTICON-cross%%\n')

        then:
            adoc.contains(':emoticon-cross: ✗')

        and: 'a page without a tick carries no definition for one'
            !adoc.contains('emoticon-tick')
    }

    def 'a document without emoticons gains nothing'() {
        expect: 'nothing in front of a page that never had one'
            AdocOutput.substitute('Just a sentence.\n') == 'Just a sentence.\n'
    }

    def 'an emoticon inside a listing block is left alone and defines nothing'() {
        given: 'a page documenting this very conversion'
            def adoc = '[source,text]\n----\n%%EMOTICON-tick%%\n----\n'

        expect: 'the sample keeps its placeholder, and no attribute is invented for it'
            AdocOutput.substitute(adoc) == adoc
    }

    def 'an emoticon used more than once is defined once'() {
        when: 'three ticks, as a status table has dozens'
            def adoc = AdocOutput.substitute(
                    '|%%EMOTICON-tick%% |%%EMOTICON-tick%% |%%EMOTICON-tick%%\n')

        then: 'all three are references'
            adoc.count('{emoticon-tick}') == 3

        and: 'and there is one definition, not one per reference'
            adoc.count('ifndef::emoticon-tick[') == 1
    }

    def 'substituting an already substituted page adds no second definition'() {
        given: 'a page read back and put through the substitution again'
            def once = AdocOutput.substitute('A cell says %%EMOTICON-tick%% here.\n')

        expect: 'the definition the first pass wrote is left alone'
            AdocOutput.substitute(once) == once
    }

    def 'every emoticon default is the glyph or the text the server offers for it'() {
        when:
            def adoc = AdocOutput.substitute("%%EMOTICON-${name}%%\n")

        then: 'measured against a DC 9.2 instance, see examples/emoticon-alt-text'
            adoc.contains("ifndef::emoticon-${name}[:emoticon-${name}: ${expected}]")

        where: 'the three read as data get a glyph, the rest the server\'s own alt text'
            name           || expected
            'tick'         || '✓'
            'cross'        || '✗'
            'warning'      || '⚠'
            'cheeky'       || '(tongue)'
            'laugh'        || '(big grin)'
            'thumbs-up'    || '(thumbs up)'
            'thumbs-down'  || '(thumbs down)'
            'information'  || '(info)'
            'light-on'     || '(lightbulb)'
            'light-off'    || '(grey lightbulb)'
            'yellow-star'  || '(star)'
            'red-star'     || '(red star)'
            'green-star'   || '(green star)'
            'blue-star'    || '(blue star)'
            'broken-heart' || '(broken heart)'
            'question'     || '(question)'
            'plus'         || '(plus)'
            'minus'        || '(minus)'
            'smile'        || '(smile)'
            'sad'          || '(sad)'
            'wink'         || '(wink)'
            'heart'        || '(heart)'
    }

    def 'an attribute reference inside a listing block defines nothing either'() {
        given: 'a page of ours showing what an exported emoticon looks like'
            def adoc = '[source,asciidoc]\n----\n{emoticon-tick}\n----\n'

        expect: 'the sample is content, not a reference this page has to define'
            AdocOutput.substitute(adoc) == adoc
    }

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

    def 'a document with a listing has both halves converted'() {
        given: """The cases above have either a listing or substitutions, never both: an
                  implementation that gave up on the whole document as soon as it saw a listing
                  would pass every one of them."""
            def adoc = '''image:before.png[]
----
image: nginx:1.25
----
image:after.png[]%%CRLF%%done
'''

        expect: 'the listing untouched, and the lines around it substituted'
            AdocOutput.substitute(adoc) == '''image::before.png[]
----
image: nginx:1.25
----
image::after.png[]
done
'''
    }

    def 'a passthrough block becomes delimiters, with nothing escaped inside'() {
        given: """Pandoc writes a plus in text as "{plus}", so the delimiters cannot travel as
                  themselves - and inside such a block nothing is AsciiDoc, so nothing in it may
                  carry an AsciiDoc escape."""
            def adoc = '%%PASSTHROUGH-BEGIN%%' +
                '++<++iframe src=\'a/2++_++b.pdf\' width=\'100++%++\' ++><++/iframe++>++' +
                '%%PASSTHROUGH-END%%'

        when:
            def out = AdocOutput.substitute(adoc)

        then:
            out == '++++\n<iframe src=\'a/2_b.pdf\' width=\'100%\' ></iframe>\n++++'
    }

    def 'a plus that pandoc spelled out is a plus again inside a passthrough'() {
        expect:
            AdocOutput.substitute('%%PASSTHROUGH-BEGIN%%a{plus}b%%PASSTHROUGH-END%%') ==
                    '++++\na+b\n++++'
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

    def 'an id that already starts with an underscore does not get a second: #name'() {
        given: '''Two underscores are AsciiDoc's unconstrained emphasis, and two such runs on one
                  line pair up. AsciiDoctor's own ids start with an underscore, so adding one
                  unconditionally made every footnote id "__footnotedef_N" - and a page writes the
                  markers of its footnotes on a single line.'''
        expect:
            AdocOutput.substitute("%%ANCHOR%%${name}%%ANCHOR-END%%").trim() == "[[${id}]]"

        where:
            name               | id
            '_footnotedef_1'   | '_footnotedef_1'
            '_footnoteref_1'   | '_footnoteref_1'
            'my_section'       | '_my_section'
            'Overview'         | '_Overview'
            '__notes'          | '_notes'
            '-beta'            | '_beta'
            '2021-goals'       | '_2021-goals'
            'a__b'             | '_a_b'
            'a++__++b'         | '_a_b'
    }

    @Requires({ AdocOutputSpec.asciidoctorIsInstalled() })
    def 'two references on one line stay two links'() {
        given: '''The shape a page of footnotes has: both markers on one line, each pointing at a
                  definition. With two underscores in each target asciidoctor read the pair as a
                  span and the two links pointed at "<em>footnotedef_2" and "</em>footnotedef_1",
                  which are anchors no document has. There is no escape that helps - a backslash
                  is consumed where it prevents markup and printed where it does not.'''
            def adoc = 'xref:page.adoc#' + AdocOutput.anchorId(names[0]) + '[2] ' +
                    'xref:page.adoc#' + AdocOutput.anchorId(names[1]) + '[1]\n'

        when:
            def rendered = render(adoc)

        then: 'two links, and neither carries emphasis the markup invented'
            rendered.count('<a href=') == 2
            !(rendered =~ /href="[^"]*<\/?em>/)

        and: 'each at the anchor the page writes for that name'
            rendered.contains('#' + AdocOutput.anchorId(names[0]))
            rendered.contains('#' + AdocOutput.anchorId(names[1]))

        where: 'every shape a name arrives in, because one underscore is not enough on its own'
            names << [['_footnotedef_2', '_footnotedef_1'],
                      ['__footnotedef_2', '__footnotedef_1'],
                      ['-footnotedef_2', '-footnotedef_1'],
                      ['a__b', 'c__d']]
    }

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    private String render(String adoc) {
        Path source = directory.resolve('anchors.adoc')
        Files.writeString(source, adoc)
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }
}
