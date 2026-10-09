package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification

/**
 * States what happens when one document wants two pages of the same title.
 *
 * A Confluence title is unique per space, so such a document cannot be published as it stands.
 * What matters is that the run says so before it writes anything: half a document in Confluence is
 * worse than none, and the page that loses is whichever section came second.
 */
class DuplicateSectionTitleSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    /** Two chapters, each with a sub-section called Purpose - an arc42 or ADR shape. */
    private static final String REPEATED_SUBSECTIONS = '''<!DOCTYPE html>
<html lang=""><head><meta charset="UTF-8"><title>Repeated</title></head><body class="article">
<div id="header"><h1>Repeated</h1></div>
<div id="content">
<div class="sect1"><h2 id="_a">Building Block A</h2><div class="sectionbody">
<div class="sect2"><h3 id="_pa">Purpose</h3><div class="sectionbody"><p>purpose of A</p></div></div>
</div></div>
<div class="sect1"><h2 id="_b">Building Block B</h2><div class="sectionbody">
<div class="sect2"><h3 id="_pb">Purpose</h3><div class="sectionbody"><p>purpose of B</p></div></div>
</div></div>
</div></body></html>'''

    /** The same, with the second heading differing in case alone. */
    private static final String REPEATED_IN_ANOTHER_CASE =
        REPEATED_SUBSECTIONS.replace('<h3 id="_pb">Purpose</h3>', '<h3 id="_pb">purpose</h3>')

    /** The titles createPage was called with, in order. */
    private List<String> created = []

    private ConfluenceClient client = Mock(ConfluenceClient)

    private Asciidoc2ConfluenceTask taskFor(Map extra = [:], String document = REPEATED_SUBSECTIONS) {
        new File("${RESOURCES}/repeated-input.html").text = document
        def config = new ConfigObject()
        config.docDir = RESOURCES
        config.confluence.api = 'https://confluence.example/rest/api/'
        config.confluence.credentials = 'x'
        config.confluence.useV1Api = true
        config.confluence.spaceKey = 'SPACE'
        config.confluence.subpagesForSections = 2
        config.confluence.input = [[file: 'repeated-input.html', ancestorId: '99']]
        extra.each { k, v -> config.confluence[k] = v }
        def task = Asciidoc2ConfluenceTask.From(config, RESOURCES)
        client.fetchPagesBySpaceKey(_, _) >> [:]
        client.fetchPagesByAncestorId(_, _) >> [:]
        client.retrievePageIdByName(_, _) >> null
        client.retrieveFullPageById(_) >> [:]
        client.createPage(_, _, _, _, _) >> [id: '1000']
        client.addLabel(_, _) >> [:]
        task.confluenceClient = client
        return task
    }

    def cleanup() {
        new File("${RESOURCES}/repeated-input.html").delete()
    }

    def 'a document that wants two pages of one title is refused before anything is written'() {
        when:
            taskFor().execute()

        then: 'the run stops, naming both headings and the title they share'
            def e = thrown(IllegalStateException)
            e.message.contains('Purpose')
            e.message.contains('Building Block A')
            e.message.contains('Building Block B')

        and: 'and nothing reached Confluence - not even the pages that come before the collision'
            0 * client.createPage(_, _, _, _, _)
            0 * client.updatePage(_, _, _, _, _, _, _)
    }

    def 'the prefix and suffix are part of the title that has to be unique'() {
        when: 'they are applied to every page equally, so they cannot resolve a collision'
            taskFor([pagePrefix: 'PROJ ', pageSuffix: ' (v2)']).execute()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('PROJ Purpose (v2)')
    }

    def 'two titles that differ in case alone are the same title'() {
        when: """The publish path is case-insensitive: existingPage looks a title up in lower
                 case, and the clients key the pages they fetched the same way. So "Purpose" and
                 "purpose" want one page just as much as two spellings that match, and a check
                 that compares them case-sensitively lets through exactly what it is for."""
            taskFor([:], REPEATED_IN_ANOTHER_CASE).execute()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('Building Block A')
            e.message.contains('Building Block B')

        and: 'the message shows a spelling the author wrote, rather than a lowercased key'
            e.message.contains("'Purpose' is wanted by")

        and:
            0 * client.createPage(_, _, _, _, _)
    }

    def 'the same titles at a level that stays on one page are no collision'() {
        when: 'subpagesForSections 1 leaves the sub-sections inside their chapter pages'
            taskFor([subpagesForSections: 1]).execute()

        then: 'the two chapter pages, and no page of its own for the repeated sub-section'
            noExceptionThrown()
            2 * client.createPage(_, _, _, _, _) >> { String title, String space, Object body,
                                                      String comment, String parent ->
                created << title
                [id: '1000']
            }

        and:
            created == ['Building Block A', 'Building Block B']
    }
}
