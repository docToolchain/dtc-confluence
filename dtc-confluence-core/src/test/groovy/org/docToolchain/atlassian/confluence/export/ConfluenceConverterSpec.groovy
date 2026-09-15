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
        return converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE).html()
    }

    private List unknownTagsIn(String storage) {
        return converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE).unknownTags()
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
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains('Another.html')
    }

    def 'a link to a page outside the export keeps its URL'() {
        given: """Exporting a subtree, a link may point at a page of the same space that is not
                  part of it. Rewriting that to a local path yields ".../.html" - a link to
                  nothing, where the original URL still works."""
            def storage = '<a href="/spaces/SPACE/pages/999/Elsewhere">elsewhere</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains('/spaces/SPACE/pages/999/Elsewhere')
            !html.contains('/.html')
    }

    def 'the child includes are written where AsciiDoc can see them'() {
        given: """Indented by four spaces they would be a literal block, and every child page
                   would silently drop out of the export."""
            def pages = ['1': [filename: 'Parent', adocFilename: 'Parent'],
                         '2': [filename: 'Chapter_10', adocFilename: 'Chapter_10', parentId: '1'],
                         '3': [filename: 'Chapter_2', adocFilename: 'Chapter_2', parentId: '1']]

        when:
            def includes = ConfluenceConverter.childIncludes('1', ['2', '3'], pages)

        then: 'no line is indented'
            includes.readLines().every { it == it.stripLeading() }

        and: 'in the order a reader reads them, not the one the API answers with'
            includes.readLines().findAll { it.startsWith('include::') } == [
                'include::Parent/Chapter_2.adoc[levelOffset=+1]',
                'include::Parent/Chapter_10.adoc[levelOffset=+1]']

        and: 'between the markers that make them optional'
            includes.readLines().contains('ifdef::includeChildren[]')
            includes.readLines().contains('endif::includeChildren[]')
    }

    def 'the file header is written where AsciiDoc can see it'() {
        given:
            def page = [title: 'A Page', position: '3']

        when:
            def header = ConfluenceConverter.fileHeader(page, 'A_Page', ['Root'], ['Root'])

        then: 'an indented attribute line is a literal block, and sets no attribute'
            header.readLines().every { it == it.stripLeading() }
            header.readLines().contains(':jbake-status: published')
            header.readLines().contains(':jbake-order: 3')
            header.readLines().contains(':filepath: Root')
    }

    def 'a link uses the name the page is written under'() {
        given: """writePage writes under adocFilename, which a stripPagePrefixRegex makes differ
                  from filename. A link built from the original name points at a file that was
                  never written."""
            def pages = ['1': [title: 'A', filename: 'PROJ_A', adocFilename: 'A'],
                         '2': [title: 'B', filename: 'PROJ_B', adocFilename: 'B']]
            def storage = '<a href="/spaces/SPACE/pages/2/B">there</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE).html()

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
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

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


    def 'the menu names every page below the one it starts at'() {
        given:
            def pages = [
                '1': [title: 'Root', filename: 'Root', adocFilename: 'Root', parentId: '0'],
                '2': [title: 'Chapter 10', filename: 'PROJ_C10', adocFilename: 'C10', parentId: '1'],
                '3': [title: 'Chapter 2', filename: 'PROJ_C2', adocFilename: 'C2', parentId: '1'],
                '4': [title: 'A Detail', filename: 'Detail', adocFilename: 'Detail', parentId: '3']]

        when:
            def menu = converter.createMenu(pages, '1')

        then: """in the order a reader reads them - the API answers children sorted by title, so
                 "Chapter 10" comes before "Chapter 2" there - one star per folder the page sits
                 in, and the name it is written under rather than the one Confluence gave it"""
            menu == '** xref:{jbake-root}Root/C2.adoc[Chapter 2]\n' +
                    '*** xref:{jbake-root}Root/C2/Detail.adoc[A Detail]\n' +
                    '** xref:{jbake-root}Root/C10.adoc[Chapter 10]\n'
    }

    def 'a root page is found by its title'() {
        expect:
            converter.findRootIdByTitle(['7': [title: 'The One']], 'The One') == '7'
    }

    def 'a title no page carries, or two do, is not a root'() {
        when:
            converter.findRootIdByTitle(pages, 'Twice')

        then:
            def e = thrown(IllegalArgumentException)
            e.message.contains(expected)

        where:
            pages                                             || expected
            ['1': [title: 'Other']]                           || 'No page found'
            ['1': [title: 'Twice'], '2': [title: 'Twice']]    || 'Multiple pages found'
    }

    def 'an export of a subtree holds that subtree and nothing else'() {
        given:
            def pages = [
                '1': [title: 'Root', parentId: '0'],
                '2': [title: 'Branch', parentId: '1'],
                '3': [title: 'Leaf', parentId: '2'],
                '4': [title: 'Elsewhere', parentId: '1']]
            def attachments = ['a': [pageId: '3', filename: 'kept.png'],
                               'b': [pageId: '4', filename: 'dropped.png']]

        when:
            def subtree = converter.filterToSubtree(pages, attachments, '2')

        then:
            subtree.pages().keySet() == ['2', '3'] as Set
            subtree.attachments().keySet() == ['a'] as Set

        and: 'the root of the subtree is a root, not a page in the middle of a tree'
            subtree.pages()['2'].parentId == 0
    }

    def 'a subtree of a page that is not in the export is not an export'() {
        when:
            converter.filterToSubtree(['1': [title: 'Root']], [:], '99')

        then:
            thrown(IllegalArgumentException)
    }

    def 'an attached file is referred to by the name it was written under'() {
        given: """The name comes from Confluence, and the file was written with its separators and
                  spaces replaced. A reference that keeps them points at a file that is not there.
                  view-file reads the name from the markup, not from the attachment map."""
            def storage = '<ac:structured-macro ac:name="view-file">' +
                '<ri:attachment ri:filename="my report.pdf" ri:version-at-save="2"/>' +
                '</ac:structured-macro>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains("2_my_report.pdf")
            !html.contains("2_my report.pdf")
    }

    def 'an image of a root page is where imagesdir already points'() {
        given: """AsciiDoc resolves an image target against imagesdir, and the file header points
                  that at the images directory. A root page sits in no folder, so naming
                  {filepath} would leave a leading slash, and naming the directory again would
                  resolve to images/images/... - neither is where the attachment was written."""
            def attachments = ['a': [pageId: '1', filename: 'shot.png', version: '3']]

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

        then:
            html.contains('src="3_shot.png"')

        where:
            storage << [
                '<ac:image><ri:attachment ri:filename="shot.png" ri:version-at-save="3"/></ac:image>',
                '<ac:structured-macro ac:name="view-file"><ri:attachment ri:filename="shot.png"' +
                    ' ri:version-at-save="3"/></ac:structured-macro>']
    }

    def 'an image of a page below the root is named relative to its folder'() {
        given:
            def pages = ['1': [title: 'Root', filename: 'Root', adocFilename: 'Root'],
                         '2': [title: 'Child', filename: 'Child', adocFilename: 'Child',
                               parentId: '1']]
            def attachments = ['a': [pageId: '2', filename: 'shot.png', version: '3']]
            def storage =
                '<ac:image><ri:attachment ri:filename="shot.png" ri:version-at-save="3"/></ac:image>'

        when:
            def html = converter.fixBody('2', storage, NO_USERS, pages, attachments, SPACE).html()

        then: 'through {filepath}, which the file header sets to the folders of the page'
            html.contains('src="{filepath}/3_shot.png"')
    }

    def 'a link between pages uses the names they are written under'() {
        given: """An ac:link names its target by title. writePage writes under adocFilename, so a
                  link built from filename points at a file that was never written."""
            def pages = ['1': [title: 'A', filename: 'PROJ_A', adocFilename: 'A'],
                         '2': [title: 'B', filename: 'PROJ_B', adocFilename: 'B', parentId: '1']]
            def storage = '<ac:link ac:anchor="part"><ri:page ri:content-title="B"/>' +
                '<ac:plain-text-link-body><![CDATA[there]]></ac:plain-text-link-body></ac:link>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains('xref:A/B.adoc#_part[there]')
            !html.contains('PROJ_')
    }

    def 'a link to a root page carries no empty folder'() {
        given: 'a root page sits in no folder, and "/Root.adoc" is a path to nothing'
            def pages = ['1': [title: 'A', filename: 'A', adocFilename: 'A'],
                         '2': [title: 'Root', filename: 'Root', adocFilename: 'Root']]
            def storage = '<ac:link><ri:page ri:content-title="Root"/>' +
                '<ac:plain-text-link-body><![CDATA[home]]></ac:plain-text-link-body></ac:link>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains('xref:Root.adoc[home]')
    }

    def 'an href to a root page carries no empty folder either'() {
        given:
            def pages = ['1': [title: 'A', filename: 'A', adocFilename: 'A'],
                         '2': [title: 'Root', filename: 'Root', adocFilename: 'Root']]
            def storage = '<a href="/spaces/SPACE/pages/2/Root">home</a>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, pages, NO_ATTACHMENTS, SPACE).html()

        then:
            html.contains('href="Root.html"')
    }
}
