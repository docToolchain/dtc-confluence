package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.docToolchain.configuration.ConfigService
import spock.lang.Shared
import spock.lang.Specification

/**
 * Pins the requests the V1 client puts on the wire, because that is its entire contract: the
 * method, the path, the query and the body are what Confluence sees.
 */
class ConfluenceClientV1RequestSpec extends Specification {

    @Shared
    HttpServer server

    @Shared
    List<Map> requests = []

    @Shared
    String responseBody = '{}'

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            requests << [
                method: exchange.requestMethod,
                uri   : exchange.requestURI.toString(),
                body  : exchange.requestBody.text
            ]
            byte[] payload = responseBody.bytes
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
        responseBody = '{}'
    }

    private ConfluenceClientV1 client() {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}/confluence", credentials: 'x']
        return new ConfluenceClientV1(new ConfigService(config))
    }

    private Map sent() { requests.first() }

    private Object sentJson() { new JsonSlurper().parseText(sent().body) }

    def 'addLabel posts the label to the page'() {
        when:
            client().addLabel('4711', [name: 'arc42'])

        then:
            sent().method == 'POST'
            sent().uri == '/confluence/rest/api/content/4711/label'
            sentJson() == [[name: 'arc42']]
    }

    def 'getAttachment asks for one file by name'() {
        when:
            client().getAttachment('4711', 'diagram.png')

        then:
            sent().method == 'GET'
            sent().uri == '/confluence/rest/api/content/4711/child/attachment?filename=diagram.png'
    }

    def 'createAttachment posts to the page attachment collection'() {
        when:
            client().createAttachment('4711', new ByteArrayInputStream('png'.bytes), 'd.png', 'note', 'abc123')

        then:
            sent().method == 'POST'
            sent().uri == '/confluence/rest/api/content/4711/child/attachment'

        and: 'the hash travels in the comment, because Confluence offers nowhere else to put it'
            sent().body.contains('note')
            sent().body.contains('#abc123#')
            sent().body.contains('d.png')
    }

    def 'updateAttachment posts to the data endpoint of an existing attachment'() {
        when:
            client().updateAttachment('4711', 'att99', new ByteArrayInputStream('png'.bytes), 'd.png', 'note', 'abc123')

        then: 'replacing an attachment is a POST as well - Confluence has no PUT for this'
            sent().method == 'POST'
            sent().uri == '/confluence/rest/api/content/4711/child/attachment/att99/data'

        and: 'and it carries the same multipart payload as a fresh upload'
            sent().body.contains('note')
            sent().body.contains('#abc123#')
            sent().body.contains('d.png')
    }

    def 'attachmentHasChanged compares the hash carried in the remote comment'() {
        given:
            def remote = [results: [[extensions: [comment: 'some note\r\n#abc123#']]]]

        expect:
            !client().attachmentHasChanged(remote, 'abc123')
            client().attachmentHasChanged(remote, 'def456')
    }

    def 'fetchPageByPageId asks for the body, the version and the ancestors'() {
        when:
            client().fetchPageByPageId('4711')

        then:
            sent().method == 'GET'
            sent().uri == '/confluence/rest/api/content/4711?expand=body.storage%2Cversion%2Cancestors'
    }

    def 'deletePage deletes by id'() {
        when:
            client().deletePage('4711')

        then:
            sent().method == 'DELETE'
            sent().uri == '/confluence/rest/api/content/4711'
    }

    def 'createPage posts a page carrying the storage format body'() {
        when:
            client().createPage('Some Page', 'SPACE', '<p>body</p>', 'a comment', '99')

        then:
            sent().method == 'POST'
            sent().uri == '/confluence/rest/api/content'

        and:
            def body = sentJson()
            body.type == 'page'
            body.title == 'Some Page'
            body.space.key == 'SPACE'
            body.body.storage.value == '<p>body</p>'
            body.body.storage.representation == 'storage'
            body.version.message == 'a comment'
            body.ancestors == [[type: 'page', id: '99']]
    }

    def 'createPage without a parent sends no ancestors'() {
        when:
            client().createPage('Some Page', 'SPACE', '<p>body</p>', '', null)

        then:
            sentJson().ancestors == null
    }

    def 'updatePage puts the new version under the page id'() {
        when:
            client().updatePage('4711', 'Some Page', 'SPACE', '<p>body</p>', 7, 'why', '99')

        then:
            sent().method == 'PUT'
            sent().uri == '/confluence/rest/api/content/4711'

        and:
            def body = sentJson()
            body.id == '4711'
            body.version.number == 7
            body.version.message == 'why'
    }

    def 'a missing version comment is sent as empty rather than as null'() {
        when:
            client().updatePage('4711', 'T', 'SPACE', '<p/>', 2, null, null)

        then:
            sentJson().version.message == ''
    }

    def 'the editor property is sent on every page write'() {
        when:
            client().createPage('T', 'SPACE', '<p/>', '', null)

        then: """Pins current behaviour. These are Cloud concepts and Data Center discards them -
                 measured against ASF Confluence 9.2.21, where the created page carried no content
                 properties at all. Harmless, but dead payload."""
            def properties = sentJson().metadata.properties
            properties.editor.value == 'v1'
            properties['content-appearance-draft'].value == 'full-width'
            properties['content-appearance-published'].value == 'full-width'
    }
}
