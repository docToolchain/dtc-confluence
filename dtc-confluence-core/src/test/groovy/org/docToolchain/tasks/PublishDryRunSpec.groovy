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

    private Asciidoc2ConfluenceTask taskFor(boolean dryRun, boolean move = false) {
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
        if (move) {
            config.confluence.moveExistingPages = true
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

    private String outputOf(Closure work) {
        def captured = new ByteArrayOutputStream()
        def previous = System.out
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            work()
        } finally {
            System.out = previous
        }
        return captured.toString('UTF-8')
    }

    def 'the run names the page the document starts at, not the space it sits in'() {
        given: '''"published to .../spaces/KEY" is where the reader has to start looking. What
                  they want is the page that was just written.'''
            client.fetchPagesBySpaceKey(_, _) >> [:]
            client.fetchPagesByAncestorId(_, _) >> [:]
            client.retrievePageIdByName(_, _) >> null
            client.createPage(_, _, _, _, _) >>> [[id: '1000'], [id: '1001'], [id: '1002']]

        when:
            def printed = outputOf { taskFor(false).execute() }

        then: 'the first page pushed, which is where the document begins'
            printed.contains('published to https://confluence.example/spaces/SPACE/pages/1000')

        and: 'and no double slash where the API URL ended in one'
            !printed.contains('//spaces/')
    }

    def 'a dry run says where it would publish, and that there is nothing to open yet'() {
        given:
            client.fetchPagesBySpaceKey(_, _) >> [:]
            client.fetchPagesByAncestorId(_, _) >> [:]
            client.retrievePageIdByName(_, _) >> null

        when:
            def printed = outputOf { taskFor(true).execute() }

        then:
            printed.contains('would publish to https://confluence.example/spaces/SPACE')
            printed.contains('do not exist yet')
    }

    def 'a page left behind by a renamed heading is named'() {
        given: '''A page is found by its title, so renaming a heading creates a new page and leaves
                  the old one where it was - the space quietly grows a second copy, and nothing
                  said so until somebody noticed it in Confluence.'''
            def leftBehind = [id: '451975151', title: 'The Old Title', parentId: '1000']
            def listing = [:]
            client.fetchPagesBySpaceKey(_, _) >> listing
            client.fetchPagesByAncestorId(_, _) >> listing
            client.retrievePageIdByName(_, _) >> null
            client.createPage(_, _, _, _, _) >>> [[id: '1000'], [id: '1001'], [id: '1002']]
            // It is in the space, below the page this document starts at, and it carries the hash
            // of the run that wrote it.
            listing['the old title'] = leftBehind
            client.retrieveFullPageById('451975151') >> [id  : '451975151', title: 'The Old Title',
                                                         body: [storage: [value:
                                                                 '<p>x</p><ac:placeholder>hash: #old#</ac:placeholder>']]]

        when:
            def printed = outputOf { taskFor(false).execute() }

        then:
            printed.contains('451975151')
            printed.contains('The Old Title')
            printed.contains('/spaces/SPACE/pages/451975151')
            printed.contains('renamed heading leaves the old page behind')
    }

    def 'a page below the document that nobody here wrote is left alone'() {
        given: 'no hash of this publisher in it, so it is not this run to talk about'
            def listing = [:]
            client.fetchPagesBySpaceKey(_, _) >> listing
            client.fetchPagesByAncestorId(_, _) >> listing
            client.retrievePageIdByName(_, _) >> null
            client.createPage(_, _, _, _, _) >>> [[id: '1000'], [id: '1001'], [id: '1002']]
            listing['somebody elses notes'] = [id: '999', title: 'Somebody elses notes',
                                               parentId: '1000']
            client.retrieveFullPageById('999') >> [id  : '999', title: 'Somebody elses notes',
                                                   body: [storage: [value: '<p>hand written</p>']]]

        when:
            def printed = outputOf { taskFor(false).execute() }

        then:
            !printed.contains('999')
            !printed.contains('written by an earlier run')
    }

    /** The title the smoke document publishes its root page under. */
    private static final String TAKEN_TITLE = 'docToolchain Confluence Smoke Test'

    /**
     * A page of that title, hanging somewhere else than where the document wants it.
     *
     * @param hash the hash this publisher left in it, or null for a page written by hand
     */
    private Map elsewhere(String hash) {
        def stored = hash == null
            ? '<p>hand written</p>'
            : "<p>x</p><ac:placeholder>hash: #${hash}#</ac:placeholder>"
        client.retrieveFullPageById('77') >> [id       : '77', title: TAKEN_TITLE,
                                              version  : [number: 2],
                                              ancestors: [[id: '12345']],
                                              body     : [storage: [value: stored]]]
        return [(TAKEN_TITLE.toLowerCase()): [id: '77', title: TAKEN_TITLE, parentId: '12345']]
    }

    def 'a title taken by a page nobody here wrote is refused, with the reason'() {
        given:
            def taken = elsewhere(null)
            client.fetchPagesBySpaceKey(_, _) >> taken
            client.fetchPagesByAncestorId(_, _) >> taken
            client.retrievePageIdByName(_, _) >> null

        when:
            taskFor(false).execute()

        then: 'the message says where the page is, and what to do about it'
            def e = thrown(IllegalArgumentException)
            e.message.contains('id 77')
            e.message.contains('/spaces/SPACE/pages/77')
            e.message.contains('it hangs under 12345')
            e.message.contains('written by somebody')
            e.message.contains('pagePrefix')

        and: 'and nothing was written'
            0 * client.createPage(_, _, _, _, _)
            0 * client.updatePage(_, _, _, _, _, _, _)
    }

    def 'a title taken by a page of an earlier run says it can be moved'() {
        given: "the page carries this publisher's hash, so it is one of ours"
            def taken = elsewhere('abc123')
            client.fetchPagesBySpaceKey(_, _) >> taken
            client.fetchPagesByAncestorId(_, _) >> taken
            client.retrievePageIdByName(_, _) >> null

        when:
            taskFor(false).execute()

        then:
            def e = thrown(IllegalArgumentException)
            e.message.contains('moveExistingPages')
            e.message.contains('--move')
    }

    def 'with the move enabled a page of an earlier run is written back under this parent'() {
        given: '''the ancestor travels with the update, so the page is written even where its
                  text did not change - skipping it would leave the page where it was'''
            def taken = elsewhere('thehashofanearlierrun')
            client.fetchPagesBySpaceKey(_, _) >> taken
            client.fetchPagesByAncestorId(_, _) >> taken
            client.retrievePageIdByName(_, _) >> null

        when:
            taskFor(false, true).execute()

        then:
            1 * client.updatePage('77', TAKEN_TITLE, 'SPACE', _, 3, _, '99')
            0 * client.createPage(TAKEN_TITLE, _, _, _, _)
    }

    def 'a dry run with the move enabled says it would move, and moves nothing'() {
        given:
            def taken = elsewhere('thehashofanearlierrun')
            client.fetchPagesBySpaceKey(_, _) >> taken
            client.fetchPagesByAncestorId(_, _) >> taken
            client.retrievePageIdByName(_, _) >> null

        when:
            def task = taskFor(true, true)
            task.execute()

        then:
            task.verdicts[Asciidoc2ConfluenceTask.Verdict.MOVE] == 1
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
