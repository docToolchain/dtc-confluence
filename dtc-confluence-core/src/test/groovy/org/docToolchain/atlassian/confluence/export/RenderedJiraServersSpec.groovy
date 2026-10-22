package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States how the base URL of a Jira is read out of a page Confluence rendered.
 *
 * The fixtures are the shape cwiki answers with, taken from the rendered view of the Maven space's
 * own pages: a macro carries the server it belongs to in its client id, and the link the server
 * built beside it.
 */
class RenderedJiraServersSpec extends Specification {

    private static final String ASF = '5aa69414-a9e9-3523-82ec-879b028fb15b'
    private static final String OTHER = '11111111-2222-3333-4444-555555555555'

    private List<String> asked = []

    private ConfluenceReader reader(String rendered) {
        Stub(ConfluenceReader) {
            fetchRenderedBody(_) >> { String pageId -> asked << pageId; rendered }
        }
    }

    private static String issueMacro(String serverId, String base, String key) {
        return '<span class="jira-issue" data-jira-key="' + key + '" data-client-id="SINGLE_' +
                serverId + '_181305684_07359"><a href="' + base + '/browse/' + key + '">' +
                key + '</a></span>'
    }

    def 'the base URL is the link the server built, up to the issue'() {
        given:
            def servers = new RenderedJiraServers(
                    reader(issueMacro(ASF, 'https://issues.apache.org/jira', 'MNG-5958')))

        expect:
            servers.baseUrlFor(ASF, '181305684') == 'https://issues.apache.org/jira'
    }

    def 'a query macro answers it just as well'() {
        given: 'six of the pages measured carry only query macros, which render a filter link'
            def rendered = '<div data-client-id="TABLE_' + ASF + '_368642456_1"><a ' +
                    'href="https://issues.apache.org/jira/issues/?jql=project%20%3D%20MNG">x</a></div>'

        expect:
            new RenderedJiraServers(reader(rendered)).baseUrlFor(ASF, '368642456') ==
                    'https://issues.apache.org/jira'
    }

    def 'a page carrying two servers answers for each the link that is its own'() {
        given: 'taking the first link on the page would give one of them the other one\'s Jira'
            def rendered = issueMacro(OTHER, 'https://jira.example.com', 'OTHER-1') +
                    issueMacro(ASF, 'https://issues.apache.org/jira', 'MNG-5958')
            def servers = new RenderedJiraServers(reader(rendered))

        expect:
            servers.baseUrlFor(ASF, '1') == 'https://issues.apache.org/jira'
            servers.baseUrlFor(OTHER, '1') == 'https://jira.example.com'
    }

    def 'a page that answers nothing does not stop a later page from answering'() {
        given: '''a count=true macro renders a placeholder and no link, and 285 of the 296 query
                  macros measured are that - so a page of them is the norm, not an edge case'''
            def countOnly = '<span data-client-id="COUNT_' + ASF + '_74686012_1">Getting issues...</span>'
            def bodies = ['74686012': countOnly,
                          '181305684': issueMacro(ASF, 'https://issues.apache.org/jira', 'MNG-5958')]
            def servers = new RenderedJiraServers(Stub(ConfluenceReader) {
                fetchRenderedBody(_) >> { String pageId -> asked << pageId; bodies[pageId] }
            })

        when: 'the page with no answer is walked first'
            def first = servers.baseUrlFor(ASF, '74686012')
            def second = servers.baseUrlFor(ASF, '181305684')

        then: 'it answers nothing, and is not taken as the answer for the server'
            first == null
            second == 'https://issues.apache.org/jira'

        and: 'both pages were asked, because the first one could not speak for the second'
            asked == ['74686012', '181305684']
    }

    def 'a page is asked once even when it answers nothing'() {
        given:
            def servers = new RenderedJiraServers(reader(
                    '<span data-client-id="COUNT_' + ASF + '_1_1">Getting issues...</span>'))

        when: 'three macros of that page, as a page of 104 of them has'
            3.times { servers.baseUrlFor(ASF, '1') }

        then:
            asked == ['1']
    }

    def 'a link in the page text is not taken for a macro\'s own'() {
        given: '''the Maven space writes Jira links by hand in table cells, and a count macro
                  renders no link of its own - on a page linking two Jiras, borrowing one would
                  give an issue of the other server a link that resolves to the wrong issue'''
            def rendered = '<span data-client-id="COUNT_' + OTHER + '_1_1">Getting issues...</span>' +
                    '<a class="external-link" href="https://evil.example.com/browse/MNG-1">MNG-1</a>' +
                    issueMacro(ASF, 'https://issues.apache.org/jira', 'MNG-5958')

        expect:
            new RenderedJiraServers(reader(rendered)).baseUrlFor(OTHER, '1') == null
    }

    def 'a link that is not a Jira link is not mistaken for one'() {
        given:
            def rendered = '<span data-client-id="SINGLE_' + ASF + '_1_1">' +
                    '<a href="/confluence/display/MAVEN/Page">relative</a>' +
                    '<a href="https://example.org/wiki/x">no browse path</a></span>'

        expect:
            new RenderedJiraServers(reader(rendered)).baseUrlFor(ASF, '1') == null
    }

    def 'nothing to go on is answered with nothing rather than a request'() {
        given:
            def servers = new RenderedJiraServers(reader('whatever'))

        expect:
            servers.baseUrlFor(serverId, pageId) == null

        and:
            asked.isEmpty()

        where:
            serverId | pageId
            null     | '1'
            ''       | '1'
            '  '     | '1'
            ASF      | null
    }

    def 'a page that is not there is answered with nothing'() {
        expect:
            new RenderedJiraServers(reader(null)).baseUrlFor(ASF, '1') == null
    }
}
