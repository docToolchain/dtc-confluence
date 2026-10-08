package org.docToolchain.tasks

import groovy.util.ConfigObject
import org.docToolchain.atlassian.confluence.export.ConfluenceReader
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * An export that converts in process, which is the point of the alternative: no pandoc anywhere.
 *
 * <p>{@link ExportConfluenceTaskSpec} is the whole export and skips itself without pandoc, so it
 * cannot say this. This spec deliberately carries no such guard - if it ever needs pandoc
 * installed to pass, the native converter is not being used.</p>
 *
 * <p>What is asserted about a line of AsciiDoc is asserted about a line, never about a substring.
 * A section title, a block attribute line, a block title and a block delimiter are markup only at
 * the start of a line, so an assertion that would pass just as well with something written in
 * front of it cannot tell a rendered document from one that shows its own markup as text.</p>
 */
class ExportConverterChoiceSpec extends Specification {

    @TempDir
    Path destination

    private ConfluenceReader reader = Mock(ConfluenceReader)

    private static final String BODY = '''
        <p>An introduction with <strong>bold</strong> in it.</p>
        <ac:structured-macro ac:name="code">
          <ac:parameter ac:name="language">groovy</ac:parameter>
          <ac:plain-text-body><![CDATA[def hello = "world"]]></ac:plain-text-body>
        </ac:structured-macro>
        <ac:structured-macro ac:name="code">
          <ac:parameter ac:name="language">bash</ac:parameter>
          <ac:plain-text-body><![CDATA[cp *.txt ${HOME}/out and H~2~O]]></ac:plain-text-body>
        </ac:structured-macro>
        <div><div class="title">A table</div></div>
        <table><tbody><tr><td>Cell</td></tr></tbody></table>
        '''

    private ExportConfluenceTask taskFor(Map exportSettings) {
        def config = new ConfigObject()
        config.confluence = [api        : 'https://confluence.example/confluence',
                             credentials: 'x', spaceKey: 'SPACE', useV1Api: true,
                             export     : [destDir: destination.toString(), rootPageId: '1']
                                     + exportSettings]
        def task = new ExportConfluenceTask(config, destination.toString())
        task.useReader(reader)
        return task
    }

    def setup() {
        reader.fetchPage('1') >> [id     : '1', title: 'Root',
                                  space  : [key: 'SPACE', name: 'A Space'],
                                  body   : [storage: [value: BODY]],
                                  history: [contributors: [publishers: [users: []]]]]
        reader.fetchChildPages('1') >> []
        reader.fetchAttachments('1') >> []
    }

    /** The AsciiDoc of the one page this spec exports, line by line. */
    private List<String> exportedLines() {
        taskFor([converter: 'native']).execute()
        return new File(destination.toFile(), 'docs/Root.adoc').getText('utf-8').readLines()
    }

    def 'an export can convert without pandoc'() {
        when:
            def lines = exportedLines()
            def adoc = lines.join('\n')

        then: 'the page is there, under the title the export gives it'
            lines.contains('== Root')

        and: 'the HTML became AsciiDoc rather than being copied through'
            adoc.contains('An introduction with *bold* in it.')
            !adoc.contains('<strong>')

        and: 'the placeholders a macro left behind were spelled out, as after pandoc'
            adoc =~ /(?m)^\[source, groovy]$/
            !adoc.contains('%%')

        and: 'the attribute line opens a listing block holding the sample and nothing else'
            adoc =~ /(?m)^\[source, groovy]\n-{4,}\ndef hello = "world"\n-{4,}$/

        and: 'and the intermediate XHTML is gone, as it is on the pandoc path'
            !new File(destination.toFile(), 'docs/Root.html').exists()
    }

    /**
     * A code sample stands between listing delimiters, where AsciiDoc substitutes nothing and
     * strips no backslash. An escape put in there is printed rather than consumed, so the shell
     * sample below would reach the page reading {@code $\{HOME}} instead of {@code ${HOME}}.
     */
    def 'a code sample is exported with the characters it was written with'() {
        expect:
            exportedLines().contains('cp *.txt ${HOME}/out and H~2~O')
    }

    /**
     * A Confluence block title becomes a {@code .Title} line, which AsciiDoc reads as a title only
     * at the start of a line and only directly above the block it belongs to. Anywhere else it is
     * a paragraph, and the block below it loses its name.
     */
    def 'a block title is a line of its own, directly above its block'() {
        given:
            def lines = exportedLines()

        expect:
            lines.contains('.A table')

        and:
            lines[lines.indexOf('.A table') + 1].startsWith('[cols=')
    }

    def 'a converter nobody implements stops the export before it writes anything'() {
        when:
            taskFor([converter: 'asciidoctorj']).execute()

        then:
            IllegalStateException e = thrown()
            e.message.contains('confluence.export.converter')

        and:
            !new File(destination.toFile(), 'docs/Root.adoc').exists()
    }
}
