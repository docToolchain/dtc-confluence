package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.docToolchain.configuration.ConfigService
import spock.lang.Shared
import spock.lang.Specification

/**
 * Pins the requests the V2 client puts on the wire. Responses are served from a queue, because the
 * constructor already issues one request to resolve the space id.
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
        responses.addAll([SPACE_LOOKUP] + laterResponses)
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}/wiki",
                             credentials: 'x', spaceKey: 'SPACE']
        return new ConfluenceClientV2(new ConfigService(config))
    }

    /** The first request is always the space lookup from the constructor. */
    private Map sent() { requests.get(1) }

    private Object sentJson() { new JsonSlurper().parseText(sent().body) }

    def 'the constructor resolves the space key to a space id'() {
        when:
            def c = client()

        then:
            requests.first().uri == '/wiki/api/v2/spaces?keys=SPACE&status=current&limit=1'
            c.spaceId == 'SPACE-1'
    }

    def 'a space key that resolves to nothing leaves the space id unset'() {
        when:
            responses.add('{"results":[]}')
            ConfigObject config = new ConfigObject()
            config.confluence = [api: "http://127.0.0.1:${server.address.port}/wiki",
                                 credentials: 'x', spaceKey: 'NOPE']
            def c = new ConfluenceClientV2(new ConfigService(config))

        then: 'no exception; the id is simply absent'
            c.spaceId == null
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
            requests[1].uri == '/wiki/rest/api/content/4711/label'
            requests[2].uri == '/wiki/rest/api/content/4711/child/attachment'
            requests[3].uri == '/wiki/rest/api/content/4711/child/attachment/att99/data'

        and: 'all three are POSTs, and both uploads carry the hash in the multipart comment'
            requests[1].method == 'POST'
            requests[2].method == 'POST'
            requests[3].method == 'POST'
            requests[2].body.contains('#h#')
            requests[3].body.contains('#h#')
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
            client().fetchPageIdByName('Some Page', 'IGNORED')

        then: 'the space key argument is unused: v2 addresses the space by id'
            sent().uri == '/wiki/api/v2/spaces/SPACE-1/pages?title=Some%20Page&status=current'
    }

    def 'createPage posts a page addressed by space id'() {
        when:
            client().createPage('Some Page', 'IGNORED', '<p>body</p>', 'a comment', '99')

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
            client().createPage('T', 'IGNORED', '<p/>', '', null)

        then:
            sentJson().parentId == ''
    }

    def 'updatePage puts the new version under the page id'() {
        when:
            client().updatePage('4711', 'Some Page', 'IGNORED', '<p>body</p>', 7, 'why', '99')

        then:
            sent().method == 'PUT'
            sent().uri == '/wiki/api/v2/pages/4711'

        and:
            def body = sentJson()
            body.id == '4711'
            body.version.number == 7
            body.version.message == 'why'
    }

    def 'fetchPagesBySpaceKey follows the cursor until there is no next link'() {
        given: 'two pages of results, the first carrying a next link'
            def first = '{"results":[{"id":"1","title":"Alpha","parentId":null}],' +
                '"_links":{"next":"/wiki/api/v2/spaces/SPACE-1/pages?cursor=CUR2"}}'
            def second = '{"results":[{"id":"2","title":"Beta","parentId":"1"}]}'

        when:
            def pages = client([first, second]).fetchPagesBySpaceKey('SPACE', 25)

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
            requests[1].uri.startsWith('/wiki/api/v2/pages/1/children')
            requests[2].uri.startsWith('/wiki/api/v2/pages/10/children')

        and: 'each page records the parent it was found under'
            pages.child == [title: 'Child', id: '10', parentId: '1']
            pages.grandchild == [title: 'Grandchild', id: '11', parentId: '10']
    }
}
