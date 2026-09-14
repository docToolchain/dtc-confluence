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
}
