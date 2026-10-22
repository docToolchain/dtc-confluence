package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.export.ConfluenceReader
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Runs a whole export: walk, attachments, conversion, pandoc, menu.
 *
 * Every piece of the export had tests of its own and none of them had ever run together - pandoc
 * was never started by a test, so what an exported document actually looks like was stated
 * nowhere. This states it.
 */
@Requires({ ExportConfluenceTaskSpec.pandocIsInstalled() })
class ExportConfluenceTaskSpec extends Specification {

    @TempDir
    Path destination

    static boolean pandocIsInstalled() {
        try {
            return new ProcessBuilder('pandoc', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    private static Map page(String id, String title, String storage) {
        return [id     : id, title: title,
                space  : [key: 'SPACE', name: 'A Space'],
                body   : [storage: [value: storage]],
                history: [contributors: [publishers: [users: []]]]]
    }

    private static final String ROOT_BODY = '''
        <p>An introduction.</p>
        <ac:structured-macro ac:name="info">
          <ac:parameter ac:name="title">Worth knowing</ac:parameter>
          <ac:rich-text-body><p>The note body.</p></ac:rich-text-body>
        </ac:structured-macro>
        <ac:structured-macro ac:name="code">
          <ac:parameter ac:name="language">groovy</ac:parameter>
          <ac:plain-text-body><![CDATA[def hello = "world"]]></ac:plain-text-body>
        </ac:structured-macro>
        <p><ac:image ac:align="center"><ri:attachment ri:filename="diagram.png"/></ac:image></p>
    '''

    private static final String CHILD_BODY = '<p>The child page.</p>'

    private ConfluenceReader reader = Mock(ConfluenceReader)

    private ExportConfluenceTask taskFor(Map exportSettings = [:]) {
        def config = new ConfigObject()
        config.confluence = [api        : 'https://confluence.example/confluence',
                             credentials: 'x', spaceKey: 'SPACE', useV1Api: true,
                             export     : [destDir: destination.toString(), rootPageId: '1']
                                     + exportSettings]
        def task = new ExportConfluenceTask(config, destination.toString())
        task.useReader(reader)
        return task
    }

    private void treeOfTwoPages() {
        reader.fetchPage('1') >> page('1', 'Root', ROOT_BODY)
        reader.fetchPage('2') >> page('2', 'Child', CHILD_BODY)
        reader.fetchChildPages('1') >> [[id: '2', title: 'Child']]
        reader.fetchChildPages('2') >> []
        reader.fetchAttachments('1') >> [[id: 'att1', title: 'diagram.png',
                                          version: [number: 3],
                                          _links : [download: '/download/attachments/1/diagram.png']]]
        reader.fetchAttachments('2') >> []
        reader.download(_) >> 'PNGBYTES'.bytes
    }

    private File exported(String path) { new File(destination.toFile(), "docs/${path}") }

    def 'a page tree becomes AsciiDoc files in the shape of the tree'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()

        then: 'the root at the top, its child in a folder named after the root'
            exported('Root.adoc').exists()
            exported('Root/Child.adoc').exists()

        and: 'the intermediate XHTML pandoc read is gone'
            !exported('Root.html').exists()
            !exported('Root/Child.html').exists()
    }

    def 'two pages whose titles become the same file name both survive'() {
        given: '''"A B" and "A-B" both sanitise to "A_B". Written under one name, the second page
                  overwrites the first while the menu goes on naming both.'''
            reader.fetchPage('1') >> page('1', 'Root', '<p>root</p>')
            reader.fetchPage('2') >> page('2', 'A B', '<p>first</p>')
            reader.fetchPage('3') >> page('3', 'A-B', '<p>second</p>')
            reader.fetchChildPages('1') >> [[id: '2', title: 'A B'], [id: '3', title: 'A-B']]
            reader.fetchChildPages('2') >> []
            reader.fetchChildPages('3') >> []
            reader.fetchAttachments(_) >> []

        when:
            taskFor().execute()

        then: 'both documents are there, and one of them says which page it is'
            exported('Root/A_B.adoc').text.contains('first')
            exported('Root/A_B_3.adoc').text.contains('second')
    }

    def 'a profile macro whose user is unknown is reported rather than dropped'() {
        given: '''The export carries no directory of users, so the name cannot be resolved. What
                  it must not do is remove the content and say nothing.'''
            reader.fetchPage('1') >> page('1', 'Root',
                '<p><ac:structured-macro ac:name="profile"><ri:user ri:userkey="abc123"/>' +
                    '</ac:structured-macro></p>')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            def task = taskFor()
            task.execute()

        then: 'the key reaches the document'
            exported('Root.adoc').text.contains('abc123')

        and: 'and the run names it among the tags it could not translate'
            task.converter.unknownTagsStats.keySet().any { it.startsWith('ac:structured-macro profile') }
    }

    def 'an exported page carries its title, its attributes and its children'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'the title as a section, not as a document header - the page is included elsewhere'
            // A whole line: a section title is only one at the start of an unindented line, and an
            // indented "== Root" renders as a literal block with the markup shown.
            adoc.readLines().contains('== Root')

        and: 'the attributes the microsite reads'
            adoc.readLines().contains(':jbake-status: published')
            adoc.readLines().contains(':filename: Root.adoc')

        and: 'the child included, between the markers that make it optional'
            adoc.readLines().contains('ifdef::includeChildren[]')
            adoc.readLines().contains('include::Root/Child.adoc[leveloffset=+1]')
            adoc.readLines().contains('endif::includeChildren[]')
    }

    def 'the macros become AsciiDoc rather than escaped punctuation'() {
        given: 'pandoc escapes a literal bracket, so this is what the placeholders are for'
            treeOfTwoPages()

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'an admonition with its title attached to its block'
            adoc.contains('.Worth knowing\n[NOTE]\n====')
            adoc.contains('The note body.')

        and: 'a source block: the attribute line, the delimiters and the code, in that order'
            adoc =~ /(?s)\[source,\s*groovy\]\s*\n-{4,}\s*\ndef hello = "world"\s*\n-{4,}/

        and: 'nothing that pandoc escaped on the way'
            !adoc.contains('++[++')
            !adoc.contains('%%')
    }

    def 'a code sample reaches the document as it was written'() {
        given: """A docker-compose sample came back as "image:: nginx:1.25" - the block-image
                  promotion fired on a line of YAML - and an indented sample came back flush
                  left, because the marker for the sample's own line breaks swallowed the spaces
                  after it. Inside a listing block AsciiDoc substitutes nothing, so neither may
                  the export."""
            reader.fetchPage('1') >> page('1', 'Root',
                '<ac:structured-macro ac:name="code">' +
                    '<ac:parameter ac:name="language">yaml</ac:parameter>' +
                    '<ac:plain-text-body><![CDATA[services:\n' +
                    '  web:\n' +
                    '    image: nginx:1.25\n' +
                    '    command: %%CRLF%%]]></ac:plain-text-body>' +
                    '</ac:structured-macro>')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'the YAML key keeps its single colon'
            adoc.contains('image: nginx:1.25')
            !adoc.contains('image:: nginx:1.25')

        and: 'every line keeps the indentation that is its meaning'
            adoc.readLines().contains('  web:')
            adoc.readLines().contains('    image: nginx:1.25')

        and: 'and a line that only looks like a placeholder is left as the sample wrote it'
            adoc.contains('command: %%CRLF%%')
    }

    def 'a file macro links to the file that was written'() {
        given: """A view-file macro becomes an iframe for a PDF and a link for anything else, and
                  both built their path from the ancestors of the page alone - so they named a
                  place the downloader no longer writes to.

                  The link branch here. The iframe of a PDF cannot be stated the same way yet:
                  it travels through pandoc as text, which escapes the underscore of the version
                  prefix, so its src reads "2++_++handbook.pdf" and names no file - before this
                  change as much as after it."""
            reader.fetchPage('1') >> page('1', 'Root', '<p>root</p>')
            reader.fetchPage('2') >> page('2', 'Child',
                '<p><ac:structured-macro ac:name="view-file">' +
                    '<ri:attachment ri:filename="handbook.zip" ri:version-at-save="2"/>' +
                    '</ac:structured-macro></p>')
            reader.fetchChildPages('1') >> [[id: '2', title: 'Child']]
            reader.fetchChildPages('2') >> []
            reader.fetchAttachments('1') >> []
            reader.fetchAttachments('2') >> [[id: 'a2', title: 'handbook.zip',
                                              version: [number: 2],
                                              _links: [download: '/download/2/handbook.zip']]]
            reader.download(_) >> 'PDFBYTES'.bytes

        when:
            taskFor().execute()
            def adoc = exported('Root/Child.adoc').getText('utf-8')

        then: 'the link, read from where the document sits, reaches the file on disk'
            def target = (adoc =~ /link:([^\[]+)\[/)[0][1]
            new File(exported('Root/Child.adoc').parentFile, target).canonicalFile.exists()
    }

    def 'two pages whose names sanitise alike keep their attachments apart'() {
        given: '''"A B" and "A-B" both become "A_B", and only the name the AsciiDoc file is
                  written under is disambiguated - so a folder named after the sanitised title
                  alone would be shared, and the two files one.'''
            def image = '<p><ac:image><ri:attachment ri:filename="diagram.png"/></ac:image></p>'
            reader.fetchPage('1') >> page('1', 'Root', '<p>root</p>')
            reader.fetchPage('2') >> page('2', 'A B', image)
            reader.fetchPage('3') >> page('3', 'A-B', image)
            reader.fetchChildPages('1') >> [[id: '2', title: 'A B'], [id: '3', title: 'A-B']]
            reader.fetchChildPages('2') >> []
            reader.fetchChildPages('3') >> []
            reader.fetchAttachments('1') >> []
            reader.fetchAttachments('2') >> [[id: 'a2', title: 'diagram.png', version: [number: 1],
                                              _links: [download: '/download/2/diagram.png']]]
            reader.fetchAttachments('3') >> [[id: 'a3', title: 'diagram.png', version: [number: 1],
                                              _links: [download: '/download/3/diagram.png']]]
            reader.download({ it.contains('/2/') }) >> 'FIRSTBYTES'.bytes
            reader.download({ it.contains('/3/') }) >> 'SECONDBYTES'.bytes

        when:
            taskFor().execute()

        then:
            def written = []
            new File(destination.toFile(), 'docs/images').eachFileRecurse {
                if (it.isFile()) {
                    written << new String(it.bytes)
                }
            }
            written as Set == ['FIRSTBYTES', 'SECONDBYTES'] as Set
    }

    def 'the attachment list of a page links to the files that were written'() {
        given: '''An attachments macro becomes a list of links, and that list builds its path
                  separately from the image references. Both have to name the same place, or the
                  list points at files that are not there.'''
            reader.fetchPage('1') >> page('1', 'Root', '<p>root</p>')
            reader.fetchPage('2') >> page('2', 'Child',
                '<p><ac:structured-macro ac:name="attachments"/></p>')
            reader.fetchChildPages('1') >> [[id: '2', title: 'Child']]
            reader.fetchChildPages('2') >> []
            reader.fetchAttachments('1') >> []
            reader.fetchAttachments('2') >> [[id: 'a2', title: 'notes.pdf', version: [number: 2],
                                              _links: [download: '/download/2/notes.pdf']]]
            reader.download(_) >> 'PDFBYTES'.bytes

        when:
            taskFor().execute()
            def adoc = exported('Root/Child.adoc').getText('utf-8')

        then: 'the link, read from where the document sits, reaches the file on disk'
            def target = (adoc =~ /link:([^\[]+)\[/)[0][1]
            new File(exported('Root/Child.adoc').parentFile, target).canonicalFile.exists()

        and:
            adoc.contains('notes.pdf (v2)')
    }

    def 'two pages with an attachment of the same name keep both files'() {
        given: """The path named the ancestors of a page but not the page itself, so two children
                  of one parent shared a directory. Two files called diagram.png at version 1
                  became one, and whichever page was walked last decided what both documents
                  showed."""
            def image = '<p><ac:image><ri:attachment ri:filename="diagram.png"/></ac:image></p>'
            reader.fetchPage('1') >> page('1', 'Root', '<p>root</p>')
            reader.fetchPage('2') >> page('2', 'First', image)
            reader.fetchPage('3') >> page('3', 'Second', image)
            reader.fetchChildPages('1') >> [[id: '2', title: 'First'], [id: '3', title: 'Second']]
            reader.fetchChildPages('2') >> []
            reader.fetchChildPages('3') >> []
            reader.fetchAttachments('1') >> []
            reader.fetchAttachments('2') >> [[id: 'a2', title: 'diagram.png', version: [number: 1],
                                              _links: [download: '/download/2/diagram.png']]]
            reader.fetchAttachments('3') >> [[id: 'a3', title: 'diagram.png', version: [number: 1],
                                              _links: [download: '/download/3/diagram.png']]]
            reader.download({ it.contains('/2/') }) >> 'FIRSTBYTES'.bytes
            reader.download({ it.contains('/3/') }) >> 'SECONDBYTES'.bytes

        when:
            taskFor().execute()

        then: '''each document reaches the bytes of its own page. Not "both files exist and the
                 two targets differ" - that holds just as well when both targets name nothing.'''
            bytesBehindTheImageIn('Root/First.adoc') == 'FIRSTBYTES'.bytes
            bytesBehindTheImageIn('Root/Second.adoc') == 'SECONDBYTES'.bytes
    }

    /**
     * The file an exported document's image macro points at, resolved the way AsciiDoc would:
     * the target against {filepath} and imagesdir, both of which the file header sets.
     */
    /**
     * The bodies of the verbatim blocks of a document: a delimiter line, the lines up to the
     * next line of the same delimiter, and nothing of what surrounds them. Asked for rather
     * than "does the text appear somewhere", which holds just as well when the delimiters
     * were lost and the sample became a paragraph.
     */
    private static List<String> verbatimBlocksIn(String adoc) {
        def blocks = []
        def matcher = adoc =~ /(?ms)^[ \t]*(-{4,}|\.{4,})[ \t]*$(.*?)^[ \t]*\1[ \t]*$/
        while (matcher.find()) {
            blocks << matcher.group(2)
        }
        return blocks
    }

    private byte[] bytesBehindTheImageIn(String document) {
        def adoc = exported(document).getText('utf-8')
        def filepath = (adoc =~ /(?m)^:filepath: (.*)$/)[0][1]
        def target = (adoc =~ /image::([^\[]+)\[/)[0][1].replace('{filepath}', filepath)
        return new File(destination.toFile(), "docs/images/${target}").bytes
    }

    def 'a PDF becomes a block the renderer passes through'() {
        given: """A view-file macro for a PDF is written as an AsciiDoc passthrough block holding
                  an iframe. The delimiters travelled through pandoc as text, which spells a plus
                  as "{plus}" - so "++++" arrived as "{plus}{plus}{plus}{plus}", which is not a
                  passthrough block, and the iframe was shown to the reader as text."""
            reader.fetchPage('1') >> page('1', 'Root',
                '<p><ac:structured-macro ac:name="view-file">' +
                    '<ri:attachment ri:filename="handbook.pdf" ri:version-at-save="2"/>' +
                    '</ac:structured-macro></p>')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> [[id: 'a1', title: 'handbook.pdf',
                                              version: [number: 2],
                                              _links: [download: '/download/1/handbook.pdf']]]
            reader.download(_) >> 'PDFBYTES'.bytes

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'the iframe stands between delimiters, rather than among {plus} signs'
            def lines = adoc.readLines().findAll { !it.trim().isEmpty() }
            def at = lines.findIndexOf { it.contains('<iframe') }
            at > 0
            lines[at - 1].trim() == '++++'
            lines[at + 1].trim() == '++++'
            !adoc.contains('{plus}')

        and: 'and it is the iframe itself, not an escaped account of one'
            !adoc.contains('++<++')
            !adoc.contains('++>')
            lines[at].contains('</iframe>')

        and: '''naming the file that was written - resolved, because a src in the wrong
                 directory contains that name just as well and loads nothing'''
            def src = (lines[at] =~ /src='([^']+)'/)[0][1]
            new File(exported('Root.adoc').parentFile, src).canonicalFile.exists()
    }

    def 'a sample that mentions the attachment marker keeps it'() {
        given: """The marker for the attachments macro was replaced everywhere in the document,
                  so a page writing it as text - a page of ours documenting this export, say -
                  had a list of attachments put in the middle of its code sample."""
            reader.fetchPage('1') >> page('1', 'Root',
                '<ac:structured-macro ac:name="code">' +
                    '<ac:parameter ac:name="language">text</ac:parameter>' +
                    '<ac:plain-text-body><![CDATA[%%attachments%%]]></ac:plain-text-body>' +
                    '</ac:structured-macro>' +
                    '<p><ac:structured-macro ac:name="attachments"/></p>')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> [[id: 'a1', title: 'notes.pdf', version: [number: 1],
                                              _links: [download: '/download/1/notes.pdf']]]
            reader.download(_) >> 'PDFBYTES'.bytes

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'the sample says what the page wrote, and says it inside the sample'
            verbatimBlocksIn(adoc).any { it.contains('%%attachments%%') }

        and: """and the macro outside it still became the list, whose link reaches the
                file - not merely a list, which holds just as well with the marker left in
                place"""
            adoc.contains('.Attachments')
            adoc.contains('notes.pdf (v1)')
            def target = (adoc =~ /link:([^\[]+)\[/)[0][1]
            new File(exported('Root.adoc').parentFile, target).canonicalFile.exists()
    }

    def 'an image embedded from a URL reaches the document as that URL'() {
        given: 'nothing to download and nothing to name: the image lives elsewhere'
            reader.fetchPage('1') >> page('1', 'Root',
                '<p><ac:image><ri:url ri:value="https://example.com/chart.png"/></ac:image></p>')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then:
            adoc.contains('image::https://example.com/chart.png[')

        and: 'not a reference to a file that was never written'
            !adoc.contains('image::1_[')
    }

    def 'an attachment is written and referred to where the document can find it'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()

        then: '''named by its version, in a folder of its page below images/, as the file header
                 points imagesdir'''
            new File(destination.toFile(), 'docs/images/Root/3_diagram.png').bytes ==
                    'PNGBYTES'.bytes

        and:
            exported('Root.adoc').getText('utf-8').contains('image::Root/3_diagram.png[')
    }

    def 'the menu names every page of the export'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()
            def menu = exported('_menu.adoc').getText('utf-8')

        then:
            menu.contains('xref:{jbake-root}Root.adoc[Root]')
            menu.contains('xref:{jbake-root}Root/Child.adoc[Child]')

        and: 'and the stylesheet the lists need is there too'
            exported('_config.adoc').exists()
    }

    def 'attachments can be left alone'() {
        given:
            treeOfTwoPages()

        when:
            taskFor([downloadAttachments: false]).execute()

        then: 'the document is written all the same, only nothing is fetched'
            exported('Root.adoc').exists()
            !new File(destination.toFile(), 'docs/images').exists()
    }

    def 'an export can start at a page named by its title'() {
        given: '''retrievePageIdByName is named after an id but used to answer the whole search
                  result. The publisher extracted the id for itself; this task did not, and asked
                  Confluence for a page whose id was a printed map.'''
            def config = new ConfigObject()
            config.confluence = [api        : 'https://confluence.example/confluence',
                                 credentials: 'x', spaceKey: 'SPACE', useV1Api: true,
                                 export     : [destDir: destination.toString(),
                                               rootPageTitle: 'The Root']]
            def client = Mock(org.docToolchain.atlassian.confluence.clients.ConfluenceClient)
            def task = new ExportConfluenceTask(config, destination.toString())
            task.confluenceClient = client
            task.useReader(reader)
            reader.fetchPage('4711') >> page('4711', 'The Root', '<p>root</p>')
            reader.fetchChildPages('4711') >> []
            reader.fetchAttachments('4711') >> []

        when:
            task.execute()

        then: '''the title is resolved and the answer is used as the id. That the client answers
                 an id at all is ConfluenceClientV1RequestSpec's business, not this test's - here
                 the client is a mock, so this case cannot see the extraction.'''
            1 * client.retrievePageIdByName('The Root', 'SPACE') >> '4711'

        and:
            new File(destination.toFile(), 'docs/The_Root.adoc').exists()
    }

    def 'a title that names no page says so'() {
        given:
            def config = new ConfigObject()
            config.confluence = [api        : 'https://confluence.example/confluence',
                                 credentials: 'x', spaceKey: 'SPACE', useV1Api: true,
                                 export     : [destDir: destination.toString(),
                                               rootPageTitle: 'Nowhere']]
            def client = Mock(org.docToolchain.atlassian.confluence.clients.ConfluenceClient)
            client.retrievePageIdByName(_, _) >> null
            def task = new ExportConfluenceTask(config, destination.toString())
            task.confluenceClient = client
            // The reader too, though the run should never reach it: without the guard the task
            // would walk from the page id "null" and this test would talk to the network.
            task.useReader(reader)

        when:
            task.execute()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('Nowhere')
            e.message.contains('SPACE')
    }

    def 'an export without a destination says so rather than writing somewhere'() {
        when:
            def config = new ConfigObject()
            config.confluence = [api: 'https://confluence.example/confluence', credentials: 'x',
                                 spaceKey: 'SPACE', useV1Api: true, export: [rootPageId: '1']]
            new ExportConfluenceTask(config, destination.toString()).execute()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('confluence.export.destDir')
    }

    def 'an export without a page to start at says so too'() {
        when:
            def config = new ConfigObject()
            config.confluence = [api: 'https://confluence.example/confluence', credentials: 'x',
                                 spaceKey: 'SPACE', useV1Api: true,
                                 export: [destDir: destination.toString()]]
            new ExportConfluenceTask(config, destination.toString()).execute()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('rootPageId')
            e.message.contains('rootPageTitle')
    }
}
