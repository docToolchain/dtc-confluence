package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification

/**
 * Publishes with a configuration that says only what it has to.
 *
 * The golden transcripts set pagePrefix and pageSuffix explicitly, so they never exercised what
 * happens when nobody writes them - which is what a minimal YAML configuration looks like.
 */
class MinimalConfigurationSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    private List<String> titles = []

    private Asciidoc2ConfluenceTask taskWithoutPrefixOrSuffix() {
        ConfigObject config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 1
        config.confluence.input = [[file: 'smoke-input.html', ancestorId: '99']]
        // No pagePrefix, no pageSuffix: both are optional, and a minimal file omits them.

        def task = Asciidoc2ConfluenceTask.From(config, RESOURCES)
        ConfluenceClient recorder = Mock(ConfluenceClient)
        recorder.fetchPagesBySpaceKey(_, _) >> [:]
        recorder.fetchPagesByAncestorId(_, _) >> [:]
        recorder.retrievePageIdByName(_, _) >> null
        recorder.retrieveFullPageById(_) >> [:]
        recorder.createPage(_, _, _, _, _) >> { String title, String spaceKey, Object body,
                                                String comment, String parentId ->
            titles << title
            return [id: '1000']
        }
        recorder.addLabel(_, _) >> [:]
        task.confluenceClient = recorder
        return task
    }

    def 'a configuration without a page prefix or suffix publishes'() {
        when:
            taskWithoutPrefixOrSuffix().execute()

        then: """An entry nobody configured is an empty ConfigObject, not null. Passed on, it either
                 fails to match the transformer's String parameter or arrives as the literal '[:]'."""
            noExceptionThrown()

        and: 'the titles are the headings, with nothing stuck to either end'
            titles == ['docToolchain Confluence Smoke Test', 'First Page', 'Second Page']
    }
}
