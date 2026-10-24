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

        then: 'as code, which is what pandoc needs to write it out verbatim'
            html.contains('<pre><code class="language-groovy">def hello = "world"</code></pre>')
    }

    def 'a noformat macro keeps its body and every character of it'() {
        given: '''the shape all 27 occurrences in the Maven space have: preformatted text in a
                  plain-text body, with no parameter and no language'''
            def storage = '<ac:structured-macro ac:name="noformat"><ac:plain-text-body>' +
                    '<![CDATA[repository\n+- org\n   \\- myproj-1.0.jar]]>' +
                    '</ac:plain-text-body></ac:structured-macro>'

        when:
            def html = convert(storage)

        then: 'as a code block, which is what pandoc writes out verbatim'
            html.contains('<pre><code>')

        and: 'with the tree that is the whole point of it, indentation intact'
            html.contains('repository\n+- org\n   \\- myproj-1.0.jar')

        and: 'and no language, because the macro names none'
            !html.contains('class="language-')
    }

    def 'a preformatted body is handed on untrimmed'() {
        given: '''a body opening and closing with a newline, which 26 of the 27 occurrences in the
                  Maven space do - Confluence puts the text of the macro on lines of its own'''
            def storage = '<ac:structured-macro ac:name="noformat"><ac:plain-text-body>' +
                    '<![CDATA[\nmvn install\n]]></ac:plain-text-body></ac:structured-macro>'

        expect: '''both newlines reach the output: what a block delimiter does with them is the
                   converter's business, and stripping them here would decide it'''
            convert(storage).contains('<pre><code>\nmvn install\n</code></pre>')
    }

    def 'a noformat macro is no longer reported as a loss'() {
        expect:
            unknownTagsIn('<ac:structured-macro ac:name="noformat">' +
                    '<ac:plain-text-body><![CDATA[x]]></ac:plain-text-body>' +
                    '</ac:structured-macro>').isEmpty()
    }

    def 'an admonition keeps its text'() {
        given:
            def storage = '''<ac:structured-macro ac:name="info">
                <ac:rich-text-body><p>Worth knowing.</p></ac:rich-text-body>
                </ac:structured-macro>'''

        expect:
            convert(storage).contains('Worth knowing.')
    }

    def 'a jira macro naming one issue becomes a link to it'() {
        given: 'the macro as the Maven space writes it, and a resolved server'
            converter.setJiraServers({ id, page -> 'https://issues.apache.org/jira' } as JiraServers)
            def storage = '<p>see <ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="server">ASF JIRA</ac:parameter>' +
                    '<ac:parameter ac:name="serverId">5aa69414-a9e9-3523-82ec-879b028fb15b</ac:parameter>' +
                    '<ac:parameter ac:name="key">MNG-5958</ac:parameter>' +
                    '</ac:structured-macro></p>'

        when:
            def html = convert(storage)

        then:
            html.contains('<a href="https://issues.apache.org/jira/browse/MNG-5958">MNG-5958</a>')

        and: '''and the parameters are gone: they used to reach the page as text, so a sentence
                read "see ASF JIRA 5aa69414-a9e9-3523-82ec-879b028fb15b MNG-5958"'''
            !html.contains('ASF JIRA')
            !html.contains('5aa69414')

        and:
            unknownTagsIn(storage).isEmpty()
    }

    def 'an issue whose server cannot be resolved keeps its key'() {
        given: 'no resolver, which is what a conversion without a server has'
            def storage = '<p><ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="key">MNG-5958</ac:parameter>' +
                    '</ac:structured-macro></p>'

        when:
            def html = convert(storage)

        then: 'the key is the content and is kept, written rather than left to leak as a parameter'
            html.contains('<span>MNG-5958</span>')

        and: 'but no link is invented that would point at some other Jira'
            !html.contains('href')
    }

    def 'a jira macro carrying a query becomes the query'() {
        given: '''296 of the 499 occurrences measured are queries rather than issues, and a query
                  is a live listing whose rows are not in the storage format at all'''
            converter.setJiraServers({ id, page -> 'https://issues.apache.org/jira' } as JiraServers)
            def storage = '<ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="serverId">5aa69414-a9e9-3523-82ec-879b028fb15b</ac:parameter>' +
                    '<ac:parameter ac:name="jqlQuery">project = MSHARED AND component = file-management </ac:parameter>' +
                    '<ac:parameter ac:name="maximumIssues">20</ac:parameter>' +
                    '<ac:parameter ac:name="columns">key,summary,status</ac:parameter>' +
                    '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then: '''as a code block a converter writes as [source,jira] - the <pre> is what makes it
                 a block rather than inline code, so it is part of the claim'''
            html.contains('<pre><code class="language-jira">' +
                    'project = MSHARED AND component = file-management</code></pre>')

        and: '''and a link to the filter, the query percent-encoded as it stands. Confluence's own
                link may differ, because the server re-serialises the JQL through Jira; both find
                the same issues'''
            html.contains('https://issues.apache.org/jira/issues/?jql=' +
                    'project%20%3D%20MSHARED%20AND%20component%20%3D%20file-management')

        and: 'the link says what it leads to, since the listing itself cannot be shown'
            html.contains('>the issues this lists</a>')

        and: '''what the listing was to look like is a property of the listing and not of the
                query, so it goes with the macro - columns is on 76 of the measured occurrences'''
            !html.contains('maximumIssues')
            !html.contains('>20<')
            !html.contains('columns')
            !html.contains('summary')
    }

    def 'a query without a resolved server is still written out'() {
        when:
            def html = convert('<ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="jqlQuery">project = MNG</ac:parameter>' +
                    '</ac:structured-macro>')

        then:
            html.contains('<pre><code class="language-jira">project = MNG</code></pre>')

        and:
            !html.contains('href')
    }

    def 'a counted query becomes an inline link where its number stood'() {
        given: '''285 of the 296 query macros measured carry count=true, which Confluence renders
                  as a bare number in the middle of a sentence'''
            converter.setJiraServers({ id, page -> 'https://issues.apache.org/jira' } as JiraServers)
            def storage = '<p>There are currently <ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="count">true</ac:parameter>' +
                    '<ac:parameter ac:name="serverId">5aa69414-a9e9-3523-82ec-879b028fb15b</ac:parameter>' +
                    '<ac:parameter ac:name="jqlQuery">filter=12338619</ac:parameter>' +
                    '</ac:structured-macro> flagged as waiting.</p>'

        when:
            def html = convert(storage)

        then: '''an inline link, labelled rather than carrying the query: a link's text is an
                 attribute list in AsciiDoc, and a query full of = renders with no text at all'''
            html.contains('<a href="https://issues.apache.org/jira/issues/?jql=filter%3D12338619">' +
                    '(count)</a>')

        and: 'and the sentence around it stays one sentence, which a block would cut in three'
            !html.contains('<pre>')
            html.contains('There are currently')
            html.contains('flagged as waiting.')
    }

    def 'a counted query with no resolved server keeps the query'() {
        when:
            def html = convert('<p><ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="count">true</ac:parameter>' +
                    '<ac:parameter ac:name="jqlQuery">filter=1</ac:parameter>' +
                    '</ac:structured-macro></p>')

        then:
            html.contains('<code>filter=1</code>')

        and:
            !html.contains('href')
    }

    def 'a jira macro that names neither an issue nor a query is reported'() {
        when:
            def unknown = unknownTagsIn('<ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="server">ASF JIRA</ac:parameter>' +
                    '</ac:structured-macro>')

        then: 'there is nothing to write, so the page says something was lost'
            unknown == ['ac:structured-macro jira']

        and: 'and the macro goes anyway, rather than leaving its parameters as text'
            !convert('<ac:structured-macro ac:name="jira">' +
                    '<ac:parameter ac:name="server">ASF JIRA</ac:parameter>' +
                    '</ac:structured-macro>').contains('ASF JIRA')
    }

    def 'an image embedded from a URL keeps the URL'() {
        given: """An ac:image names either an attachment of the page or, with ri:url, an image
                  somewhere else. Only the attachment was read, so the URL became an "1_" - a
                  reference to a file that was never there. The publisher writes ri:url for any
                  source starting with http, so this is the other half of that round trip."""
            def storage = '<p><ac:image ac:align="center">' +
                '<ri:url ri:value="https://example.com/chart.png"/></ac:image></p>'

        expect:
            convert(storage).contains('src="https://example.com/chart.png"')
    }

    def 'a quote in an image reference cannot add attributes of its own'() {
        given: """jsoup decodes ri:value before the translation runs, so a stored URL carrying
                  &apos; closed the src attribute of the fragment that was written out, and
                  everything after it was read as further attributes - a page could put an
                  onerror into the exported HTML."""
            def storage = '<p><ac:image><ri:url ri:value="https://example.com/x.png?a=&apos;' +
                ' onerror=&apos;alert(1)"/></ac:image></p>'

        when: 'parsed, because what matters is the attributes the document ends up with'
            def img = org.jsoup.Jsoup.parse(convert(storage)).selectFirst('img')

        then: "one src attribute carrying the whole value, and no attribute it invented"
            img.attr('src') == "https://example.com/x.png?a=' onerror='alert(1)"
            img.attr('onerror').isEmpty()
    }

    def 'a quote in an attachment name cannot add attributes either'() {
        given: 'the same for the other branch, where the name comes from the storage format'
            def storage = '<p><ac:image><ri:attachment' +
                ' ri:filename="a&apos; onerror=&apos;alert(1).png"/></ac:image></p>'

        when:
            def img = org.jsoup.Jsoup.parse(convert(storage)).selectFirst('img')

        then: '''the whole name reaches the src - sanitizeAttachmentName turns the space into an
                 underscore, so an attribute smuggled in here would be called "_onerror" and
                 asking for "onerror" alone would pass on a truncated src'''
            img.attr('src') == "A_Page/1_a'_onerror='alert(1).png"

        and: 'and the element carries what it was built with, and nothing the value invented'
            img.attributes()*.key as Set == ['src', 'align', 'width'] as Set
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

    def 'an emoticon reaches the document instead of being left to the converter'() {
        given: 'a table cell answering its column with a tick, shaped as the Maven space writes it'
            def storage = '<table><tbody><tr><th>Done?</th></tr>' +
                    '<tr><td><p> <ac:emoticon ac:name="tick" /> </p></td></tr></tbody></table>'

        when:
            def html = convert(storage)

        then: 'as the placeholder that survives pandoc and becomes an attribute reference'
            html.contains('%%EMOTICON-tick%%')

        and: 'and the export no longer reports a loss it does not suffer'
            unknownTagsIn(storage).isEmpty()
    }

    def 'the placeholder stands where the emoticon stood'() {
        given: 'a link, a tick and a remark, as they stand on the Branch Protection page'
            def storage = '<p><a href="https://example.org/1">PR</a> ' +
                    '<ac:emoticon ac:name="tick" /> + branches (TBD)</p>'

        when:
            def html = convert(storage)

        then: 'in place of the element, with the text on either side untouched'
            html.contains('PR</a> %%EMOTICON-tick%% + branches (TBD)')
    }

    def 'an emoticon this class cannot name an attribute after is reported, not dropped quietly'() {
        when:
            def result = converter.fixBody('1', "<p>before${emoticon}after</p>",
                    NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)

        then: 'the tag goes, because it has no spelling in AsciiDoc'
            result.html().contains('beforeafter')

        and: 'but the page says that something was lost, which is this project\'s one guarantee'
            result.unknownTags().any { it.startsWith('ac:emoticon') }

        where: 'a brace, an underscore pandoc would escape, and no name at all'
            emoticon << ['<ac:emoticon ac:name="smile}{" />',
                         '<ac:emoticon ac:name="thumbs_up" />',
                         '<ac:emoticon />']
    }

    def 'a name that was dropped is named in the report'() {
        when:
            def unknown = unknownTagsIn('<p><ac:emoticon ac:name="thumbs_up" /></p>')

        then: 'so that the summary says which emoticon went, not only that one did'
            unknown == ['ac:emoticon thumbs_up']
    }

    def 'an emoticon name is read without regard to case'() {
        when: 'a name the palette spells in lower case'
            def html = convert('<p><ac:emoticon ac:name="Tick" /></p>')

        then: 'the same placeholder, rather than a tag reported as unusable'
            html.contains('%%EMOTICON-tick%%')
    }

    def 'an emoticon name cannot carry markup into the page'() {
        when: 'a name built to close the attribute reference and open an element'
            def html = convert('<p><ac:emoticon ac:name="tick%%<b>x</b>" /></p>')

        then: 'it is refused outright, so nothing of it is parsed'
            !html.contains('<b>')
            !html.contains('EMOTICON')
    }

    def 'a heading loses a hand-written chapter number'() {
        when:
            def html = convert('<h2>5.2.4. Deployment</h2>')

        then: 'AsciiDoc numbers sections itself, so the prefix carried over is noise'
            html.contains('Deployment')
            !html.contains('5.2.4')
    }

    def 'a heading that starts with a year keeps it'() {
        given: """A chapter number and a year look alike to a regular expression, and the page
                  "2024 Roadmap" came back titled "Roadmap". A number spelled out over four
                  digits and without a dot is a year, not the fourth level of a chapter."""
        expect:
            convert('<h2>2024 Roadmap</h2>').contains('2024 Roadmap')
            convert('<h1>1999 in review</h1>').contains('1999 in review')
    }

    def 'a chapter number is still stripped in the shapes a document writes it'() {
        expect: 'the number goes and the title stays - losing the whole heading is not a pass'
            convert(heading).contains('Deployment')
            !convert(heading).contains(number)

        where:
            heading                        | number
            '<h2>5.2.4. Deployment</h2>'   | '5.2.4'
            '<h2>5.2 Deployment</h2>'      | '5.2'
            '<h2>5. Deployment</h2>'       | '5.'
            '<h2>5 Deployment</h2>'        | '5 '
            '<h2>12 Deployment</h2>'       | '12'
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
                'include::Parent/Chapter_2.adoc[leveloffset=+1]',
                'include::Parent/Chapter_10.adoc[leveloffset=+1]']

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

        and: """the title on the first line: a level-0 heading is a document title there and a
                refused section anywhere else"""
            header.readLines().first() == '= A Page'

        and: """a blank line between the attributes and the first include, which ends the header -
                the style block that include carries is read inside the header without it, and
                then the pass block is unterminated and the CSS reaches the page as text.
                ChildIncludeLevelSpec renders what this line is for."""
            def lines = header.readLines()
            lines[lines.indexOf('include::{jbake-root}_config.adoc[]') - 1] == ''
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

        then: 'the three lines reach pandoc as the lines of a code block'
            html.contains('<pre><code class="language-groovy">def a = 1\ndef b = 2\ndef c = 3')
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

    def 'the menu of a page at the root of the export carries no empty folder'() {
        given: '"{jbake-root}/Root.adoc" is a path to nothing'
            def pages = ['1': [title: 'Root', filename: 'Root', adocFilename: 'Root',
                               parentId: '0']]

        expect:
            converter.createMenu(pages, '0') == '* xref:{jbake-root}Root.adoc[Root]\n'
    }

    def 'a code macro hands its language on in the form pandoc reads'() {
        given:
            def storage = '<ac:structured-macro ac:name="code">' +
                '<ac:parameter ac:name="language">groovy</ac:parameter>' +
                '<ac:plain-text-body><![CDATA[def x = 1]]></ac:plain-text-body>' +
                '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then: '''as the class of a code block, which is how pandoc knows to write the attribute
                 line itself - written out here it would be a literal bracket in text, and pandoc
                 escapes those'''
            html.contains('<pre><code class="language-groovy">')
            !html.contains('[source')
    }

    def 'a language parameter cannot carry markup into the page'() {
        given: """The language used to be interpolated into the markup of the code block, so a
                  value ending the class attribute put whatever followed it into the exported
                  page - as markup, outside the sample."""
            def storage = '<ac:structured-macro ac:name="code">' +
                '<ac:parameter ac:name="language">groovy"&gt;&lt;/code&gt;&lt;/pre&gt;' +
                '&lt;h2&gt;Extra&lt;/h2&gt;&lt;pre&gt;&lt;code class="language-groovy' +
                '</ac:parameter>' +
                '<ac:plain-text-body><![CDATA[def x = 1]]></ac:plain-text-body>' +
                '</ac:structured-macro>'

        when:
            def dom = org.jsoup.Jsoup.parse(convert(storage))

        then: 'one code block, and no heading of the value\'s making'
            dom.select('pre > code').size() == 1
            dom.select('h2').isEmpty()

        and: 'the value is the class, in full, meaning itself'
            dom.selectFirst('pre > code').attr('class').startsWith('language-groovy">')
    }

    def 'a Lucidchart macro asked for as an iframe travels as a passthrough placeholder'() {
        given:
            converter.lucidChartsIframe = true
            def storage = '<ac:structured-macro ac:name="lucidchart">' +
                '<ac:parameter ac:name="documentId">abc-123</ac:parameter>' +
                '</ac:structured-macro>'

        when:
            def html = convert(storage)

        then: '''the iframe is between the placeholders - not merely somewhere in the document,
                 because the document id is in the metadata and the edit link as well'''
            def between = html.substring(html.indexOf('%%PASSTHROUGH-BEGIN%%'),
                    html.indexOf('%%PASSTHROUGH-END%%'))
            between.contains('iframe')
            between.contains('abc-123')

        and: 'pandoc would spell the delimiters out as {plus} signs and show the iframe as text'
            !html.contains('++++')
    }

    def 'a code macro without a language is still a code block'() {
        given: 'no language parameter at all, which Confluence allows'
            def storage = '<ac:structured-macro ac:name="code">' +
                '<ac:plain-text-body><![CDATA[plain text]]></ac:plain-text-body>' +
                '</ac:structured-macro>'

        expect: 'no class, rather than an empty one pandoc would write out as a language'
            convert(storage).contains('<pre><code>plain text</code></pre>')
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

    def 'an image of a root page is named by the folder of that page alone'() {
        given: """AsciiDoc resolves an image target against imagesdir, and the file header points
                  that at the images directory. A root page sits in no folder of its own
                  ancestors, so naming {filepath} would leave a leading slash and naming the
                  directory again would resolve to images/images/... - but the attachments of the
                  page do sit in a folder named after it."""
            def attachments = ['a': [pageId: '1', filename: 'shot.png', version: '3']]

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

        then:
            html.contains('src="A_Page/3_shot.png"')

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

        then: '''through {filepath}, which the file header sets to the folders of the page, and
                 then the folder of the page itself'''
            html.contains('src="{filepath}/Child/3_shot.png"')
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

    def 'an inline comment keeps the ref that anchors it'() {
        given: 'a marker as the stored body of the Maven Ecosystem Cleanup page writes it'
            def storage = '<p>we need to keep its "bridging" capability (see ' +
                    '<ac:inline-comment-marker ac:ref="8277a302-9001-4aff-84ef-2b62f77ec7fc">' +
                    'Drop</ac:inline-comment-marker> ServiceLocator)</p>'

        when:
            def html = convert(storage)

        then: 'as a role, which is what pandoc carries out and Asciidoctor renders back'
            html.contains('class="inline-comment-marker comment-ref-8277a302-9001-4aff-84ef-2b62f77ec7fc"')

        and: 'with the marked words where they stood'
            html.contains('(see <span class="inline-comment-marker ' +
                    'comment-ref-8277a302-9001-4aff-84ef-2b62f77ec7fc">Drop</span> ServiceLocator)')
    }

    def 'the space at the edge of a marked phrase stays outside the span'() {
        given: '''a comment anchored to "widely " - Confluence lets the marked text end in a space,
                  and pandoc writes such a span constrained, which AsciiDoc does not read as a span
                  at all: one of that page's eight markers was lost to it'''
            def storage = '<p>this is <ac:inline-comment-marker ac:ref="498c3ceb">widely ' +
                    '</ac:inline-comment-marker>used</p>'

        when:
            def html = convert(storage)

        then: 'the words are marked and the space is not'
            html.contains('this is <span class="inline-comment-marker ' +
                    'comment-ref-498c3ceb">widely</span> used')
    }

    def 'a marked phrase that is nothing but space is left as it is'() {
        given: 'because there is no word to move the space off'
            def storage = '<p>a<ac:inline-comment-marker ac:ref="498c3ceb"> ' +
                    '</ac:inline-comment-marker>b</p>'

        expect:
            convert(storage).contains('>a<span class="inline-comment-marker ' +
                    'comment-ref-498c3ceb"> </span>b<')
    }

    def 'a marker carries no id, so one comment may mark several places'() {
        given: '''one comment split over two elements, which is how a stored body writes an anchor
                  whose text spans inline markup - three of the eight markers on that page are one
                  comment'''
            def storage = '<p>this is <ac:inline-comment-marker ac:ref="498c3ceb">widely' +
                    '</ac:inline-comment-marker> <em>very</em> ' +
                    '<ac:inline-comment-marker ac:ref="498c3ceb">used</ac:inline-comment-marker></p>'

        when:
            def html = convert(storage)

        then: 'both places name the comment'
            html.count('comment-ref-498c3ceb') == 2

        and: '''and neither names it as an id: AsciiDoctor rejects a duplicate id, so the identity
                travels as a role'''
            !html.contains('id=')
    }

    def 'a marker is no longer reported as a loss'() {
        expect:
            unknownTagsIn('<p><ac:inline-comment-marker ac:ref="6cc14366-cefd">x' +
                    '</ac:inline-comment-marker></p>').isEmpty()
    }

    def 'a marker whose ref cannot be carried is reported, not dropped quietly'() {
        when:
            def result = converter.fixBody('1', "<p>before${marker}after</p>",
                    NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)

        then: 'the words stay, because they are the page'
            result.html().contains('beforemarkedafter')

        and: '''but the page says the anchor went: all 38 markers on the 136 pages measured carry a
                ref, so a marker without a usable one is worth a word'''
            result.unknownTags() == ['ac:inline-comment-marker']

        where: 'no ref, an empty one, and one that would be two roles'
            marker << ['<ac:inline-comment-marker>marked</ac:inline-comment-marker>',
                       '<ac:inline-comment-marker ac:ref="">marked</ac:inline-comment-marker>',
                       '<ac:inline-comment-marker ac:ref="a b">marked</ac:inline-comment-marker>']
    }

    def 'a ref cannot carry markup into the page'() {
        when: 'a ref built to close the class attribute and open an element'
            def html = convert('<p><ac:inline-comment-marker ac:ref=\'x"><b>y</b>\'>marked' +
                    '</ac:inline-comment-marker></p>')

        then: 'it is refused outright, so nothing of it is parsed'
            !html.contains('<b>')
            !html.contains('comment-ref-')

        and: 'and the marked words are still there'
            html.contains('marked')
    }

    def 'a drawio macro refers to the picture that carries its diagram'() {
        given: '''By then DiagramMerge has put the mxfile inside that picture, so the document
                  refers to one file and publishing it again writes the macro back.'''
            def attachments = ['9': [filename: 'arch.png', originalFilename: 'arch.png',
                                     version: '2', pageId: '1']]
            def storage = '<ac:structured-macro ac:name="drawio">' +
                '<ac:parameter ac:name="diagramName">arch</ac:parameter>' +
                '<ac:parameter ac:name="diagramWidth">941</ac:parameter></ac:structured-macro>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

        then:
            html.contains('<img src="A_Page/2_arch.png" width="941" />')
    }

    def 'a macro pinned to one view refers to that view, not to the first page of the file'() {
        given: '''Measured on a real page: one diagram, three macros, two of them pinned with an
                  aspect, and the app gives each pinned one a preview of its own named
                  <diagram>-<sha1 of the aspect>.png. Without that, two of the three spots in the
                  exported document showed a view the page does not show there.'''
            def pinned = 'arch-62167aa2f85e4935ba9c7c2eba7bb76551487513.png'
            def attachments = ['9': [filename: 'arch.png', originalFilename: 'arch.png',
                                     version: '2', pageId: '1'],
                               '10': [filename: pinned, originalFilename: pinned,
                                      version: '1', pageId: '1']]
            def storage = '<ac:structured-macro ac:name="drawio">' +
                '<ac:parameter ac:name="diagramName">arch</ac:parameter>' +
                '<ac:parameter ac:name="aspect">view-core</ac:parameter></ac:structured-macro>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

        then:
            html.contains('src="A_Page/1_' + pinned + '"')
    }

    def 'a pinned view with no preview of its own falls back to the diagram\'s'() {
        given: '''The app draws such a spot from the mxfile and leaves no static picture at all,
                  so the first view is a better answer than none.'''
            def attachments = ['9': [filename: 'arch.png', originalFilename: 'arch.png',
                                     version: '2', pageId: '1']]
            def storage = '<ac:structured-macro ac:name="drawio">' +
                '<ac:parameter ac:name="diagramName">arch</ac:parameter>' +
                '<ac:parameter ac:name="aspect">view-core</ac:parameter></ac:structured-macro>'

        when:
            def html = converter.fixBody('1', storage, NO_USERS, PAGES, attachments, SPACE).html()

        then:
            html.contains('src="A_Page/2_arch.png"')
    }
}
