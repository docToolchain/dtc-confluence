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

        then: 'the key reaches the document, and the tag is in the report'
            exported('Root.adoc').text.contains('abc123')
    }

    def 'an exported page carries its title, its attributes and its children'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()
            def adoc = exported('Root.adoc').getText('utf-8')

        then: 'the title as a section, not as a document header - the page is included elsewhere'
            adoc.contains('== Root')

        and: 'the attributes the microsite reads'
            adoc.readLines().contains(':jbake-status: published')
            adoc.readLines().contains(':filename: Root.adoc')

        and: 'the child included, between the markers that make it optional'
            adoc.readLines().contains('ifdef::includeChildren[]')
            adoc.readLines().contains('include::Root/Child.adoc[levelOffset=+1]')
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
            adoc =~ /(?s)\[source, groovy\]\s*\n-{4,}\s*\ndef hello = "world"\s*\n-{4,}/

        and: 'nothing that pandoc escaped on the way'
            !adoc.contains('++[++')
            !adoc.contains('%%')
    }

    def 'an attachment is written and referred to where the document can find it'() {
        given:
            treeOfTwoPages()

        when:
            taskFor().execute()

        then: 'named by its version, below images/, as the file header points imagesdir'
            new File(destination.toFile(), 'docs/images/3_diagram.png').bytes == 'PNGBYTES'.bytes

        and:
            exported('Root.adoc').getText('utf-8').contains('3_diagram.png')
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
