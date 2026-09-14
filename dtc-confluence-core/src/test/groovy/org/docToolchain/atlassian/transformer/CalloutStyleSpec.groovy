package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * States what happens to a callout marker on its way into a Confluence code macro.
 *
 * A macro holds plain text, so a marker cannot be both shown and left out of the clipboard the way
 * Asciidoctor's HTML manages. These are the three ways of giving up one or the other.
 */
class CalloutStyleSpec extends Specification {

    /**
     * What Asciidoctor really renders a callout as, taken from the generated showcase: an empty i
     * carrying the number as an attribute, and a sibling b carrying what is shown. CSS hides the b
     * and generates the marker from the attribute - which is why a browser copies clean code.
     */
    private static String calloutOf(int number) {
        return "<i class=\"conum\" data-value=\"${number}\"></i><b>(${number})</b>"
    }

    private static org.jsoup.nodes.Element blockOf(String language, String code) {
        def dom = Jsoup.parse(
            "<div><div class=\"listingblock\"><div class=\"content\">" +
            "<pre class=\"highlight\"><code class=\"language-${language}\" data-lang=\"${language}\">" +
            code + "</code></pre></div></div></div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private static String transform(String language, String code, CalloutStyle style) {
        def body = blockOf(language, code)
        return new HtmlTransformer().withCallouts(style)
            .transformToConfluenceFormat(body, [:], [:], '', '')
    }

    def 'comment is what an unset configuration gets'() {
        expect: 'changing what existing documents publish should be a decision, not an upgrade'
            CalloutStyle.from(null) == CalloutStyle.COMMENT
    }

    def 'a configured style is taken, whatever its spelling'() {
        expect:
            CalloutStyle.from(configured) == style

        where:
            configured      || style
            'comment'       || CalloutStyle.COMMENT
            'linenumbers'   || CalloutStyle.LINENUMBERS
            'expand'        || CalloutStyle.EXPAND
            ' EXPAND '      || CalloutStyle.EXPAND
            'LineNumbers'   || CalloutStyle.LINENUMBERS
    }

    def 'an unusable value falls back rather than failing the publish'() {
        expect:
            CalloutStyle.from('nonsense') == CalloutStyle.COMMENT
    }

    def 'the comment character is the one the language actually uses'() {
        when:
            def result = transform(language, "a line ${calloutOf(1)}", CalloutStyle.COMMENT)

        then: 'docToolchain wrote // into every language, which a shell does not ignore'
            result.contains(expected)

        where:
            language || expected
            'bash'   || 'a line # (1)'
            'py'     || 'a line # (1)'
            'yml'    || 'a line # (1)'
            'sql'    || 'a line -- (1)'
            'java'   || 'a line // (1)'
            'groovy' || 'a line // (1)'
            'xml'    || 'a line <!-- (1) -->'
            'css'        || 'a line /* (1) */'
            'coldfusion' || 'a line <!--- (1) --->'
            'applescript'|| 'a line -- (1)'
    }

    def 'a comment that has to be closed is closed'() {
        expect: 'an unterminated comment would swallow the rest of the block'
            transform(language, "first ${calloutOf(1)}\nsecond", CalloutStyle.COMMENT)
                .contains('second')

        where:
            language << ['xml', 'css', 'sass', 'coldfusion']
    }

    def 'linenumbers leaves the code exactly as written'() {
        when:
            def result = transform('bash', "echo one ${calloutOf(1)}\necho two ${calloutOf(2)}",
                CalloutStyle.LINENUMBERS)

        then: 'nothing of the marker survives, so the block can be copied and run'
            !result.contains('(1)')
            !result.contains('(2)')
            !result.contains('#')
            result.contains('echo one')
            result.contains('echo two')

        and: 'and the macro numbers its lines, so the callout list can refer to them'
            result.contains('<ac:parameter ac:name="linenumbers">true</ac:parameter>')
    }

    def 'linenumbers is not switched on for a block that has no callouts'() {
        expect:
            !transform('bash', 'echo one', CalloutStyle.LINENUMBERS).contains('linenumbers')
    }

    def 'expand publishes the annotated block and a copyable one'() {
        when:
            def result = transform('bash', "echo one ${calloutOf(1)}", CalloutStyle.EXPAND)

        then: 'the block as written, markers and all'
            result.contains('echo one # (1)')

        and: 'followed by a folded copy without them'
            result.contains('<ac:structured-macro ac:name="expand">')
            result.contains('without the callout markers')
            result.count('ac:name="code"') == 2

        and: 'and that copy is the bare code'
            result.split('ac:name="expand"')[1].contains('echo one')
            !result.split('ac:name="expand"')[1].contains('(1)')
    }

    def 'expand adds nothing to a block without callouts'() {
        expect:
            !transform('bash', 'echo one', CalloutStyle.EXPAND).contains('ac:name="expand"')
    }

    def 'a block without callouts is unchanged whatever the style'() {
        expect:
            transform('java', 'int x = 1;', style).contains('int x = 1;')

        where:
            style << CalloutStyle.values()
    }

    def 'a shell continuation gets a copyable copy even under comment'() {
        given: """Measured against the generated showcase: Asciidoctor strips the # of the
                  \\#<1> convention while rendering, so the marker sits straight after the
                  backslash with no space between them."""
            def code = "./build verify \\${calloutOf(1)}" + "\n  --offline \\${calloutOf(2)}"

        when:
            def result = transform('bash', code, CalloutStyle.COMMENT)

        then: 'the block as written, with its markers'
            result.contains('./build verify \\ # (1)')

        and: 'and a folded copy underneath'
            result.contains('<ac:structured-macro ac:name="expand">')
            result.count('ac:name="code"') == 2

        and: """The backslash has to be the last character on its line. A copy ending in
                 backslash-space escapes the space instead of continuing the command, and would
                 not run any better than the annotated block does."""
            def copy = result.split('ac:name="expand"')[1]
            !copy.contains('(1)')
            !copy.contains('\\ ')
            copy.contains('./build verify \\' + '\n')
    }

    def 'a marker written behind a space takes that space with it'() {
        given: 'the spelling without a comment character, which a document may still use'
            def code = "./build verify \\ ${calloutOf(1)}" + "\n  --offline"

        when:
            def copy = transform('bash', code, CalloutStyle.COMMENT).split('ac:name="expand"')[1]

        then: 'otherwise the copy ends in backslash-space and does not run either'
            !copy.contains('\\ ')
            copy.contains('./build verify \\')
    }

    def 'a shell block whose callouts are not behind a continuation is left as comments'() {
        when:
            def result = transform('bash', "echo one ${calloutOf(1)}", CalloutStyle.COMMENT)

        then: 'nothing is wrong with that block, so nothing is added'
            result.contains('echo one # (1)')
            !result.contains('ac:name="expand"')
    }

    def 'a continuation in a language that has no such thing changes nothing'() {
        when: 'a backslash in Java is an escape, not a continuation'
            def result = transform('java', "String s = \"a\\\\\" ${calloutOf(1)}",
                CalloutStyle.COMMENT)

        then:
            !result.contains('ac:name="expand"')
    }

    def 'linenumbers is left alone by the continuation rule'() {
        when: 'it already produces a block that can be pasted'
            def result = transform('bash', "./build verify \\ ${calloutOf(1)}",
                CalloutStyle.LINENUMBERS)

        then:
            !result.contains('ac:name="expand"')
            result.contains('<ac:parameter ac:name="linenumbers">true</ac:parameter>')
    }

    def 'PowerShell is not a backslash-continuation language'() {
        given: """It continues a line with a backtick; a trailing backslash there is a path
                  separator. Treating it as a continuation would move the block off the configured
                  style for no reason."""
            def code = "Get-Item C:\\\\ ${calloutOf(1)}"

        when:
            def result = transform('powershell', code, CalloutStyle.COMMENT)

        then: 'plain comments, and no second macro'
            result.contains('# (1)')
            !result.contains('ac:name="expand"')
    }

    def 'expand keeps its copy for a continuation, as comment does'() {
        when:
            def result = transform('bash', "./build verify \\${calloutOf(1)}",
                CalloutStyle.EXPAND)

        then:
            result.contains('<ac:structured-macro ac:name="expand">')
            result.count('ac:name="code"') == 2
    }
}
