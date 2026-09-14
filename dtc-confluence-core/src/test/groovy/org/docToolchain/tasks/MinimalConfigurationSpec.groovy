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

    /** A one-pixel PNG as a data URI, which is the path that reaches the image store. */
    private static final String EMBEDDED_IMAGE_DOCUMENT = '''<!DOCTYPE html>
<html lang=""><head><meta charset="UTF-8"><title>Embedded</title></head><body class="article">
<div id="header"><h1>Embedded</h1></div>
<div id="content"><div class="sect1"><h2 id="_s">S</h2><div class="sectionbody">
<div class="imageblock"><div class="content">
<img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" alt="Dot">
</div></div>
</div></div></div></body></html>'''

    private Asciidoc2ConfluenceTask taskWithoutPrefixOrSuffix() {
        ConfigObject config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 1
        config.confluence.input = [[file: 'smoke-input.html', ancestorId: '99']]
        // No pagePrefix, no pageSuffix, no imageDirs: all optional, and a minimal file omits them.

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

    def 'a configuration without imageDirs publishes an embedded image'() {
        given: """Coercing an empty ConfigObject with 'as List' builds a proxy whose iterator
                  throws, which surfaces far from the cause - in the image store."""
            def task = taskWithoutPrefixOrSuffix()
            new File("${RESOURCES}/embedded-input.html").text = EMBEDDED_IMAGE_DOCUMENT
            task.configService.@config.confluence.input = [[file: 'embedded-input.html', ancestorId: '99']]

        when:
            task.execute()

        then:
            noExceptionThrown()

        cleanup:
            new File("${RESOURCES}/embedded-input.html").delete()
            new File("${RESOURCES}/images").deleteDir()
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
