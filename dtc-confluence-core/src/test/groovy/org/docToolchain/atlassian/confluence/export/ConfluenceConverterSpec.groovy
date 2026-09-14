package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * Characterises the conversion of Confluence storage format on its way back to AsciiDoc.
 *
 * The fixtures are the storage format this project itself publishes, so these say whether a
 * document survives the round trip: publish, then export.
 *
 * fixBody is the half that needs no pandoc. It prepares the XHTML and reports the tags it did not
 * recognise, which is where a round trip loses things quietly.
 */
class ConfluenceConverterSpec extends Specification {

    private ConfluenceConverter converter = new ConfluenceConverter()

    private static final Map NO_USERS = [:]
    private static final Map NO_ATTACHMENTS = [:]
    private static final Map SPACE = [key: 'SPACE', name: 'A Space']
    private static final Map PAGES = ['1': [title: 'A Page', filename: 'A_Page', adocFilename: 'A_Page']]

    private String convert(String storage) {
        def result = converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)
        return result[0] as String
    }

    private List unknownTagsIn(String storage) {
        def result = converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)
        return result[1] as List
    }

    def 'a code macro keeps its code and its language'() {
        given: 'what the publisher writes for a source block'
            def storage = '''<ac:structured-macro ac:name="code">
                <ac:parameter ac:name="language">groovy</ac:parameter>
                <ac:plain-text-body><![CDATA[def hello = "world"]]></ac:plain-text-body>
                </ac:structured-macro>'''

        when:
            def html = convert(storage)

        then:
            html.contains('def hello = "world"')
            html.contains('groovy')
    }

    def 'an admonition keeps its text'() {
        given:
            def storage = '''<ac:structured-macro ac:name="info">
                <ac:rich-text-body><p>Worth knowing.</p></ac:rich-text-body>
                </ac:structured-macro>'''

        expect:
            convert(storage).contains('Worth knowing.')
    }

    def 'a tag it does not know is reported rather than dropped in silence'() {
        given: 'a macro this converter was never taught'
            def storage = '''<ac:structured-macro ac:name="chart">
                <ac:parameter ac:name="type">pie</ac:parameter>
                </ac:structured-macro>'''

        when:
            def unknown = unknownTagsIn(storage)

        then: 'a round trip that loses something should say so'
            !unknown.isEmpty()
            !converter.unknownTagsStats.isEmpty()
    }

    def 'a heading loses a hand-written chapter number'() {
        when:
            def html = convert('<h2>5.2.4. Deployment</h2>')

        then: 'AsciiDoc numbers sections itself, so the prefix carried over is noise'
            html.contains('Deployment')
            !html.contains('5.2.4')
    }

    def 'chapter numbering is kept where the document means it'() {
        given:
            converter.stripChapterNumbering = false

        expect:
            convert('<h2>5.2.4. Deployment</h2>').contains('5.2.4')
    }

    def 'an empty heading is removed'() {
        expect:
            !convert('<h1> </h1><p>text</p>').contains('<h1>')
    }

    def 'a colspan of one is dropped, because it says nothing'() {
        expect:
            !convert('<table><tr><td colspan="1">a</td></tr></table>').contains('colspan="1"')
    }

    def 'a link within the export becomes a local path'() {
        given:
            def pages = ['1': [title: 'A Page', filename: 'A_Page', adocFilename: 'A_Page'],
                         '2': [title: 'Another', filename: 'Another', adocFilename: 'Another']]
            def storage = '<a href="/spaces/SPACE/pages/2/Another">there</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE)[0] as String

        then:
            html.contains('Another.html')
    }

    def 'a link to a page outside the export keeps its URL'() {
        given: """Exporting a subtree, a link may point at a page of the same space that is not
                  part of it. Rewriting that to a local path yields ".../.html" - a link to
                  nothing, where the original URL still works."""
            def storage = '<a href="/spaces/SPACE/pages/999/Elsewhere">elsewhere</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)[0] as String

        then:
            html.contains('/spaces/SPACE/pages/999/Elsewhere')
            !html.contains('/.html')
    }

    def 'the child includes are written where AsciiDoc can see them'() {
        expect: """Indented by four spaces they would be a literal block, and every child page
                   would silently drop out of the export."""
            def source = new File('src/main/groovy/org/docToolchain/atlassian/confluence/export/ConfluenceConverter.groovy')
            source.readLines().any { it == 'ifdef::includeChildren[]' }
            source.readLines().any { it == ':jbake-status: published' }
    }

    def 'a link uses the name the page is written under'() {
        given: """writePage writes under adocFilename, which a stripPagePrefixRegex makes differ
                  from filename. A link built from the original name points at a file that was
                  never written."""
            def pages = ['1': [title: 'A', filename: 'PROJ_A', adocFilename: 'A'],
                         '2': [title: 'B', filename: 'PROJ_B', adocFilename: 'B']]
            def storage = '<a href="/spaces/SPACE/pages/2/B">there</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE)[0] as String

        then:
            html.contains('B.html')
            !html.contains('PROJ_B.html')
    }

    def 'an image macro resolves its attachment'() {
        given: 'what the publisher writes for a local image'
            def attachments = ['9': [filename: 'diagram.png', originalFilename: 'diagram.png',
                                     id: '9', pageId: '1', version: '1']]
            def storage = '<ac:image ac:align="center" ac:width="360">' +
                '<ri:attachment ri:filename="diagram.png" /></ac:image>'

        when: "select takes a query string; a list literal would not even dispatch"
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE)[0] as String

        then:
            noExceptionThrown()
            html.contains('diagram.png')
    }

    def 'a code block keeps its line breaks'() {
        given: """A review reported that text() collapses these. Measured, it does not:
                  for a CDATA body - which is what Confluence writes - text() and wholeText()
                  are identical. This states the behaviour rather than a fix."""
            def storage = '<ac:structured-macro ac:name="code">' +
                '<ac:parameter ac:name="language">groovy</ac:parameter>' +
                '<ac:plain-text-body><![CDATA[def a = 1\ndef b = 2\ndef c = 3]]></ac:plain-text-body>' +
                '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then: 'two breaks between the three lines, marked for the step after pandoc'
            html.count('%%CRLF%%') >= 5
            html.contains('def a = 1%%CRLF%%def b = 2%%CRLF%%def c = 3')
    }

    def 'a code sample containing markup stays a code sample'() {
        given: 'the body is interpolated into element.html(), which parses what it is given'
            def storage = '<ac:structured-macro ac:name="code">' +
                '<ac:parameter ac:name="language">xml</ac:parameter>' +
                '<ac:plain-text-body><![CDATA[<af:button id="x"/>]]></ac:plain-text-body>' +
                '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then: 'it survives as text rather than being reparsed as a tag'
            html.contains('&lt;af:button')
            !html.contains('<af:button')
    }

    def 'a Lucidchart macro converts without a log file to write to'() {
        given: 'lucidInfoFile is documented as null to log nothing'
            def storage = '<ac:structured-macro ac:name="lucidchart">' +
                '<ac:parameter ac:name="documentId">abc-123</ac:parameter>' +
                '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then:
            noExceptionThrown()
            html != null
    }
}
