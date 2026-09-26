package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.docToolchain.configuration.ConfigService
import spock.lang.Shared
import spock.lang.Specification

/**
 * Pins the requests the V2 client puts on the wire. Responses are served from a queue, in the order
 * the calls under test make them.
 *
 * <p>Only some calls address the space, and those resolve the configured key to an id first -
 * {@link #spaceAddressingClient} queues that lookup, {@link #client} does not.</p>
 */
class ConfluenceClientV2RequestSpec extends Specification {

    private static final String SPACE_LOOKUP = '{"results":[{"id":"SPACE-1"}]}'

    @Shared
    HttpServer server

    @Shared
    List<Map> requests = []

    @Shared
    List<String> responses = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            requests << [
                method: exchange.requestMethod,
                uri   : exchange.requestURI.toString(),
                body  : exchange.requestBody.text
            ]
            byte[] payload = (responses.isEmpty() ? '{}' : responses.remove(0)).bytes
            exchange.sendResponseHeaders(200, payload.length)
            exchange.responseBody.withStream { it.write(payload) }
        }
        server.start()
    }

    def cleanupSpec() {
        server?.stop(0)
    }

    def setup() {
        requests.clear()
        responses.clear()
    }

    private ConfluenceClientV2 client(List<String> laterResponses = []) {
        responses.addAll(laterResponses)
        return clientFor('SPACE')
    }

    /** For calls that address the space: the key is resolved to an id before the call itself. */
    private ConfluenceClientV2 spaceAddressingClient(List<String> laterResponses = []) {
        responses.addAll([SPACE_LOOKUP] + laterResponses)
        return clientFor('SPACE')
    }

    private ConfluenceClientV2 clientFor(String spaceKey) {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}/wiki",
                             credentials: 'x', spaceKey: spaceKey]
        return new ConfluenceClientV2(new ConfigService(config))
    }

    /** The call under test is the last one made. */
    private Map sent() { requests.last() }

    private Object sentJson() { new JsonSlurper().parseText(sent().body) }

    def 'building the client asks Confluence nothing'() {
        when:
            clientFor('SPACE')

        then: """A client is also built to check credentials. Resolving the space here would let a
                 missing or forbidden space fail before that check ever ran."""
            requests.isEmpty()
    }

    def 'the space key is resolved when a call first needs the id'() {
        given:
            responses.add(SPACE_LOOKUP)
            def c = clientFor('SPACE')

        when:
            def id = c.spaceId

        then:
            id == 'SPACE-1'
            requests.size() == 1
            requests.first().uri == '/wiki/api/v2/spaces?keys=SPACE&status=current&limit=1'
    }

    def 'the space is resolved once per key, not on every call'() {
        given:
            responses.addAll([SPACE_LOOKUP, '{}', '{}'])
            def c = clientFor('SPACE')

        when:
            c.fetchPageIdByName('One', 'SPACE')
            c.fetchPageIdByName('Two', 'SPACE')

        then: 'one lookup, then one request per call'
            requests.size() == 3
            requests.first().uri.startsWith('/wiki/api/v2/spaces?keys=SPACE')
    }

    def 'a call that names another space asks about that one'() {
        given: '''An input may publish into a space of its own. Resolving the configured key
                  whatever was asked for put the pages of one space into another.'''
            responses.addAll([SPACE_LOOKUP, '{}', '{"results":[{"id":"OTHER-2"}]}', '{}'])
            def c = clientFor('SPACE')

        when:
            c.fetchPageIdByName('One', 'SPACE')
            c.fetchPageIdByName('Two', 'OTHER')

        then: 'two lookups, one per key, and the second call addresses the second space'
            requests*.uri.findAll { it.startsWith('/wiki/api/v2/spaces?keys=') } ==
                    ['/wiki/api/v2/spaces?keys=SPACE&status=current&limit=1',
                     '/wiki/api/v2/spaces?keys=OTHER&status=current&limit=1']
            requests.last().uri.startsWith('/wiki/api/v2/spaces/OTHER-2/pages')
    }

    def 'a space key that resolves to nothing leaves the space id unset'() {
        given:
            responses.add('{"results":[]}')

        when:
            def c = clientFor('NOPE')

        then: 'no exception; the id is simply absent'
            c.spaceId == null
    }

    def 'a space that resolves to nothing is not looked up again and again'() {
        given: 'only one response is queued, so a second lookup would read the wrong one'
            responses.add('{"results":[]}')
            def c = clientFor('NOPE')

        when:
            c.spaceId
            c.spaceId

        then:
            requests.size() == 1
    }

    def 'getAttachment uses the v2 attachment collection'() {
        when:
            client().getAttachment('4711', 'diagram.png')

        then:
            sent().uri == '/wiki/api/v2/pages/4711/attachments?filename=diagram.png'
    }

    def 'attachments and labels are written through the v1 API'() {
        when:
            def c = client()
            c.addLabel('4711', [name: 'arc42'])
            c.createAttachment('4711', new ByteArrayInputStream('x'.bytes), 'd.png', 'note', 'h')
            c.updateAttachment('4711', 'att99', new ByteArrayInputStream('x'.bytes), 'd.png', 'note', 'h')

        then: """Pins current behaviour. v2 has no equivalent for these, so the v2 client falls back
                 to v1 paths - which is why a Cloud instance that dropped v1 would break here."""
            requests[0].uri == '/wiki/rest/api/content/4711/label'
            requests[1].uri == '/wiki/rest/api/content/4711/child/attachment'
            requests[2].uri == '/wiki/rest/api/content/4711/child/attachment/att99/data'

        and: 'all three are POSTs, and both uploads carry the hash in the multipart comment'
            requests[0].method == 'POST'
            requests[1].method == 'POST'
            requests[2].method == 'POST'
            requests[1].body.contains('#h#')
            requests[2].body.contains('#h#')
    }

    def 'attachmentHasChanged reads the comment directly, unlike v1'() {
        given:
            def remote = [results: [[comment: 'note\r\n#abc123#']]]

        expect: 'v1 nests the same field under extensions; v2 does not'
            !client().attachmentHasChanged(remote, 'abc123')
            client().attachmentHasChanged(remote, 'def456')
    }

    def 'fetchPageByPageId asks for the storage body format'() {
        when:
            client().fetchPageByPageId('4711')

        then:
            sent().uri == '/wiki/api/v2/pages/4711?body-format=storage'
    }

    def 'deletePage deletes by id'() {
        when:
            client().deletePage('4711')

        then:
            sent().method == 'DELETE'
            sent().uri == '/wiki/api/v2/pages/4711'
    }

    def 'fetchPageIdByName searches within the resolved space'() {
        when:
            spaceAddressingClient().fetchPageIdByName('Some Page', 'IGNORED')

        then: 'the space key argument is unused: v2 addresses the space by id'
            sent().uri == '/wiki/api/v2/spaces/SPACE-1/pages?title=Some%20Page&status=current'
    }

    def 'createPage posts a page addressed by space id'() {
        when:
            spaceAddressingClient().createPage('Some Page', 'IGNORED', '<p>body</p>', 'a comment', '99')

        then:
            sent().method == 'POST'
            sent().uri == '/wiki/api/v2/pages'

        and:
            def body = sentJson()
            body.title == 'Some Page'
            body.status == 'current'
            body.spaceId == 'SPACE-1'
            body.parentId == '99'
            body.body.value == '<p>body</p>'
            body.body.representation == 'storage'
            body.version.number == 1
            body.version.message == 'a comment'
    }

    def 'a page without a parent sends an empty parent id rather than null'() {
        when:
            spaceAddressingClient().createPage('T', 'IGNORED', '<p/>', '', null)

        then:
            sentJson().parentId == ''
    }

    def 'updatePage puts the new version under the page id'() {
        when:
            spaceAddressingClient().updatePage('4711', 'Some Page', 'SPACE', '<p>body</p>', 7, 'why', '99')

        then:
            sent().method == 'PUT'
            sent().uri == '/wiki/api/v2/pages/4711'

        and: """An update carries the space id too: createPage and updatePage share one request
                 body builder, and both resolve the key they were given."""
            requests.size() == 2
            requests.first().uri.startsWith('/wiki/api/v2/spaces?keys=SPACE')

        and:
            def body = sentJson()
            body.id == '4711'
            body.spaceId == 'SPACE-1'
            body.version.number == 7
            body.version.message == 'why'
    }

    def 'fetchPagesBySpaceKey follows the cursor until there is no next link'() {
        given: 'two pages of results, the first carrying a next link'
            def first = '{"results":[{"id":"1","title":"Alpha","parentId":null}],' +
                '"_links":{"next":"/wiki/api/v2/spaces/SPACE-1/pages?cursor=CUR2"}}'
            def second = '{"results":[{"id":"2","title":"Beta","parentId":"1"}]}'

        when:
            def pages = spaceAddressingClient([first, second]).fetchPagesBySpaceKey('SPACE', 25)

        then: 'the cursor from the next link is carried into the following request'
            requests[1].uri == '/wiki/api/v2/spaces/SPACE-1/pages?depth=all&limit=25'
            requests[2].uri == '/wiki/api/v2/spaces/SPACE-1/pages?depth=all&limit=25&cursor=CUR2'

        and: 'pages are keyed by lower-case title'
            pages.keySet() == ['alpha', 'beta'] as Set
            pages.alpha == [title: 'Alpha', id: '1', parentId: null]
            pages.beta == [title: 'Beta', id: '2', parentId: '1']
    }

    def 'fetchPagesByAncestorId walks into the children it discovered'() {
        given:
            def parent = '{"results":[{"id":"10","title":"Child"}]}'
            def child = '{"results":[{"id":"11","title":"Grandchild"}]}'
            def empty = '{"results":[]}'

        when:
            def pages = client([parent, child, empty, empty]).fetchPagesByAncestorId(['1'], 25)

        then:
            requests[0].uri.startsWith('/wiki/api/v2/pages/1/children')
            requests[1].uri.startsWith('/wiki/api/v2/pages/10/children')

        and: 'each page records the parent it was found under'
            pages.child == [title: 'Child', id: '10', parentId: '1']
            pages.grandchild == [title: 'Grandchild', id: '11', parentId: '10']
    }
}
