package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification

/**
 * States that confluence.callouts reaches the page.
 *
 * CalloutStyleSpec covers what each style produces; this covers the one line that carries the
 * setting from the configuration into the transformer. Without it a lookup or coercion mistake
 * would leave the advertised option inert while every style test stayed green.
 */
class CalloutConfigurationSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    private List<String> bodies = []

    private Asciidoc2ConfluenceTask taskFor(Object callouts) {
        ConfigObject config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 0
        config.confluence.input = [[file: 'callout-input.html', ancestorId: '99']]
        if (callouts != null) {
            config.confluence.callouts = callouts
        }

        def task = Asciidoc2ConfluenceTask.From(config, RESOURCES)
        ConfluenceClient recorder = Mock(ConfluenceClient)
        recorder.fetchPagesBySpaceKey(_, _) >> [:]
        recorder.fetchPagesByAncestorId(_, _) >> [:]
        recorder.retrievePageIdByName(_, _) >> null
        recorder.retrieveFullPageById(_) >> [:]
        recorder.createPage(_, _, _, _, _) >> { String title, String spaceKey, Object body,
                                                String comment, String parentId ->
            bodies << (body as String)
            return [id: '1000']
        }
        recorder.addLabel(_, _) >> [:]
        task.confluenceClient = recorder
        return task
    }

    def setup() {
        bodies = []
    }

    def 'the configured style is what the page gets'() {
        when:
            taskFor(configured).execute()

        then:
            bodies.join('').contains(expected)

        where:
            configured    || expected
            null          || 'echo one # (1)'
            'comment'     || 'echo one # (1)'
            'linenumbers' || '<ac:parameter ac:name="linenumbers">true</ac:parameter>'
    }

    def 'expand gives the page a second code macro'() {
        when:
            taskFor('expand').execute()

        then:
            bodies.join('').count('ac:name="code"') == 2
            bodies.join('').contains('without the callout markers')
    }

    def 'a value that names no style falls back rather than failing the publish'() {
        when:
            taskFor('nonsense').execute()

        then:
            noExceptionThrown()
            bodies.join('').contains('echo one # (1)')
    }
}
