package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification

/**
 * Records what the publisher would send to Confluence for a real AsciiDoctor document, and
 * compares it against a checked-in transcript.
 *
 * <p>This is the net for porting Asciidoc2ConfluenceTask. The class is 823 lines of dynamic Groovy
 * with no coverage at all; asserting individual behaviours would take a hundred tests and still
 * miss the interactions. A transcript catches any change to what reaches Confluence, which is the
 * only thing that matters here.</p>
 *
 * <p>Run with {@code -Dgolden.update=true} to rewrite the transcripts after an intended change.
 * Review the diff: it is the change, stated in full.</p>
 */
class PublisherGoldenFileSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    private Asciidoc2ConfluenceTask taskFor(int subpagesForSections) {
        // Nested properties are assigned one by one so that the intermediate nodes are
        // ConfigObjects, as they are in production. A map literal would make absent keys null,
        // and the deprecation guard for allInOnePage/createSubpages tests for ConfigObject.
        ConfigObject config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = subpagesForSections
        config.confluence.pagePrefix = ''
        config.confluence.pageSuffix = ''
        config.confluence.input = [[file: 'smoke-input.html', ancestorId: '99']]
        return Asciidoc2ConfluenceTask.From(config, RESOURCES)
    }

    /**
     * Runs a publish with a client that records the writes instead of performing them.
     *
     * @return a transcript of everything that would have reached Confluence
     */
    private String transcriptFor(int subpagesForSections) {
        List<String> transcript = []
        int nextId = 1000

        ConfluenceClient recorder = Mock(ConfluenceClient)
        recorder.fetchPagesBySpaceKey(_, _) >> [:]
        recorder.fetchPagesByAncestorId(_, _) >> [:]
        recorder.retrievePageIdByName(_, _) >> null
        recorder.retrieveFullPageById(_) >> [:]
        recorder.createPage(_, _, _, _, _) >> { String title, String spaceKey, Object body,
                                                String comment, String parentId ->
            transcript << "CREATE page '${title}' space=${spaceKey} parent=${parentId} comment='${comment}'"
            transcript << (body as String).readLines().collect { "    | ${it}" }.join('\n')
            return [id: String.valueOf(nextId++)]
        }
        recorder.addLabel(_, _) >> { pageId, label ->
            transcript << "LABEL on ${pageId}: ${label}"
            return [:]
        }

        def task = taskFor(subpagesForSections)
        // confluenceClient is a plain property, so the recorder simply takes its place.
        task.confluenceClient = recorder
        task.execute()
        return transcript.join('\n') + '\n'
    }

    private void assertMatchesGolden(String name, String actual) {
        File golden = new File("${RESOURCES}/${name}")
        if (System.getProperty('golden.update') == 'true' || !golden.exists()) {
            golden.parentFile.mkdirs()
            golden.text = actual
            println "golden file written: ${golden}"
        }
        assert actual == golden.text
    }

    def 'one page per top-level section'() {
        expect:
            assertMatchesGolden('smoke-subpages-1.txt', transcriptFor(1))
    }

    def 'everything on a single page'() {
        expect:
            assertMatchesGolden('smoke-subpages-0.txt', transcriptFor(0))
    }

    def 'a page per section and sub-section'() {
        expect:
            assertMatchesGolden('smoke-subpages-2.txt', transcriptFor(2))
    }
}
