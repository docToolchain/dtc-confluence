package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification

/**
 * States that a dry run reads everything and writes nothing, and what it says about each page.
 *
 * The point of a dry run is answering "what would this change?" before it changes it, so the
 * interesting assertions are the ones about writes that do NOT happen.
 */
class PublishDryRunSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    private ConfluenceClient client = Mock(ConfluenceClient)

    private Asciidoc2ConfluenceTask taskFor(boolean dryRun) {
        def config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 1
        config.confluence.input = [[file: 'smoke-input.html', ancestorId: '99']]
        if (dryRun) {
            config.confluence.dryRun = true
        }
        def task = Asciidoc2ConfluenceTask.From(config, RESOURCES)
        task.confluenceClient = client
        return task
    }

    /** A page Confluence would answer with, carrying the hash the publisher wrote into it. */
    private static Map remotePage(String id, String hash) {
        return [id     : id, title: 'A Page', version: [number: 4],
                ancestors: [[id: '99']],
                body   : [storage: [value: "<p>x</p><ac:placeholder>hash: #${hash}#</ac:placeholder>"]]]
    }

    def 'a dry run creates nothing, updates nothing and attaches nothing'() {
        given: 'a space where none of the pages exist yet'
            client.fetchPagesBySpaceKey(_, _) >> [:]
            client.fetchPagesByAncestorId(_, _) >> [:]
            client.retrievePageIdByName(_, _) >> null

        when:
            def task = taskFor(true)
            task.execute()

        then: 'nothing is written, at all'
            0 * client.createPage(_, _, _, _, _)
            0 * client.updatePage(_, _, _, _, _, _, _)
            0 * client.createAttachment(_, _, _, _, _)
            0 * client.updateAttachment(_, _, _, _, _, _)
            0 * client.addLabel(_, _)

        and: 'and every page is reported as one that would be created'
            task.verdicts[Asciidoc2ConfluenceTask.Verdict.CREATE] > 0
            task.verdicts[Asciidoc2ConfluenceTask.Verdict.UPDATE] == null
    }

    def 'the same run without the flag writes'() {
        given: 'the same fixtures, so the difference is the flag and nothing else'
            client.fetchPagesBySpaceKey(_, _) >> [:]
            client.fetchPagesByAncestorId(_, _) >> [:]
            client.retrievePageIdByName(_, _) >> null
            client.createPage(_, _, _, _, _) >> [id: '1000']

        when:
            taskFor(false).execute()

        then:
            (1.._) * client.createPage(_, _, _, _, _)
    }

    def 'a page that is already there with the same text is reported as unchanged'() {
        given: """the publisher writes the hash of what it produced into the page, so what a dry
                  run compares is what a real run would compare"""
            def published = [:]
            client.fetchPagesBySpaceKey(_, _) >> published
            client.fetchPagesByAncestorId(_, _) >> published
            client.retrievePageIdByName(_, _) >> null
            client.createPage(_, _, _, _, _) >> { String title, String space, Object body,
                                                  String comment, String parent ->
                def hash = (body as String) =~ /hash: #([^#]+)#/
                published[title.toLowerCase()] = [id: '1000', title: title, parentId: parent]
                client.retrieveFullPageById('1000') >> remotePage('1000', hash[0][1])
                return [id: '1000']
            }

        when: 'publish once for real, then ask what a second run would do'
            taskFor(false).execute()
            def dry = taskFor(true)
            dry.execute()

        then:
            dry.verdicts[Asciidoc2ConfluenceTask.Verdict.UNCHANGED] > 0
            dry.verdicts[Asciidoc2ConfluenceTask.Verdict.CREATE] == null
            0 * client.updatePage(_, _, _, _, _, _, _)
    }

    def 'a title that is taken elsewhere is reported rather than thrown'() {
        given: """publishing would fail on it - a title is unique per space. A dry run that stopped
                  at the first problem would hide the others, which is the opposite of the point."""
            def title = 'docToolchain Confluence Smoke Test'
            def taken = [(title.toLowerCase()): [id: '77', title: title, parentId: '12345']]
            client.fetchPagesBySpaceKey(_, _) >> taken
            client.fetchPagesByAncestorId(_, _) >> taken
            client.retrievePageIdByName(_, _) >> null

        when:
            def task = taskFor(true)
            task.execute()

        then:
            noExceptionThrown()
            task.verdicts[Asciidoc2ConfluenceTask.Verdict.CONFLICT] > 0
    }
}
