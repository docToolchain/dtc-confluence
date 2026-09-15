package org.docToolchain.atlassian.confluence.export

import com.sun.net.httpserver.HttpServer
import org.docToolchain.atlassian.confluence.clients.RestClient
import org.docToolchain.configuration.ConfigService
import spock.lang.Shared
import spock.lang.Specification

/**
 * Pins the requests the export puts on the wire, and how it walks a paged collection.
 *
 * A real server answers here rather than a mock, because paging is the part that goes wrong and it
 * only goes wrong across several responses.
 */
class ConfluenceReaderSpec extends Specification {

    @Shared
    HttpServer server

    @Shared
    List<Map> requests = []

    @Shared
    List<Object> responses = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            requests << [uri: exchange.requestURI.toString()]
            Object next = responses.isEmpty() ? '{}' : responses.remove(0)
            // A queued response is either bytes to hand back untouched - an attachment is not
            // text - or a string, which may name its status: "404:" for a page that is not there.
            int status = 200
            byte[] payload
            if (next instanceof byte[]) {
                payload = next
            } else {
                String body = next as String
                if (body.startsWith('404:')) {
                    status = 404
                    body = body.substring(4)
                }
                payload = body.bytes
            }
            exchange.sendResponseHeaders(status, payload.length)
            exchange.responseBody.withStream { it.write(payload) }
        }
        server.start()
    }

    def cleanupSpec() { server?.stop(0) }

    def setup() {
        requests.clear()
        responses.clear()
    }

    private ConfluenceReader reader(int pageLimit = 2) {
        def config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}/confluence",
                             credentials: 'x', spaceKey: 'SPACE']
        def service = new ConfigService(config)
        return new ConfluenceReader(service, new RestClient(service), pageLimit)
    }

    def 'a page is asked for with everything the converter needs'() {
        given:
            responses << '{"id":"1","title":"A Page"}'

        when:
            def page = reader().fetchPage('1')

        then:
            page.title == 'A Page'

        and: 'body, history and space in one request, rather than one request each'
            requests.first().uri.startsWith('/confluence/rest/api/content/1?expand=')
            requests.first().uri.contains('body.storage')
            requests.first().uri.contains('history.contributors.publishers.users')
            requests.first().uri.contains('space')
    }

    def 'a page that is not there is not an error'() {
        given: 'a subtree may name a page that was deleted'
            responses << '404:{"message":"No content found"}'

        expect:
            reader().fetchPage('404') == null
    }

    def 'children are followed to the end of the paging'() {
        given: 'two full pages of results and then a short one'
            responses << '{"results":[{"id":"10"},{"id":"11"}]}'
            responses << '{"results":[{"id":"12"},{"id":"13"}]}'
            responses << '{"results":[{"id":"14"}]}'

        when:
            def children = reader(2).fetchChildPages('1')

        then:
            children*.id == ['10', '11', '12', '13', '14']

        and: 'asked for from the right offsets'
            requests[0].uri.contains('start=0')
            requests[1].uri.contains('start=2')
            requests[2].uri.contains('start=4')
    }

    def 'a short first page ends the walk at once'() {
        given:
            responses << '{"results":[{"id":"10"}]}'

        when:
            def children = reader(2).fetchChildPages('1')

        then: 'fewer results than asked for means there are no more'
            children*.id == ['10']
            requests.size() == 1
    }

    def 'no children at all is an empty list, not a failure'() {
        given:
            responses << '{"results":[]}'

        expect:
            reader().fetchChildPages('1').isEmpty()
    }

    def 'attachments are asked for with their version'() {
        given: 'the version decides whether a downloaded file is still current'
            responses << '{"results":[{"id":"att1","title":"a.png"}]}'

        when:
            def attachments = reader().fetchAttachments('1')

        then:
            attachments*.title == ['a.png']
            requests.first().uri.contains('/child/attachment')
            requests.first().uri.contains('expand=version')
    }

    def 'an exactly full page still asks once more'() {
        given: 'because a full page is indistinguishable from more to come'
            responses << '{"results":[{"id":"10"},{"id":"11"}]}'
            responses << '{"results":[]}'

        when:
            def children = reader(2).fetchChildPages('1')

        then:
            children*.id == ['10', '11']
            requests.size() == 2
    }

    def 'a download link is asked for below the context path'() {
        given: """Confluence hands out /download/attachments/... , which is relative to the
                  path it is served under - /confluence on a typical Data Center. Without it the
                  request reaches nothing, and every attachment of an export is lost."""
            responses << 'BYTES'

        when:
            def content = reader().download('/download/attachments/1/a.png?version=1')

        then:
            new String(content) == 'BYTES'
            requests.first().uri == '/confluence/download/attachments/1/a.png?version=1'
    }

    def 'an attachment comes back as the bytes it is'() {
        given: """A PNG is not text. Were the transport to decode the response and encode it
                  again, every byte above 0x7F would come back as the replacement character and
                  the image on disk would be broken."""
            byte[] png = [0x89, 0x50, 0x4E, 0x47, 0x00, 0xFF, 0x80, 0x7F] as byte[]
            responses << png

        when:
            byte[] content = reader().download('/download/attachments/1/a.png')

        then:
            content == png
    }

    def 'a link below a path that merely starts like the context is given its own'() {
        given: 'a context of /confluence does not cover /confluence-other'
            responses << 'BYTES'

        when:
            reader().download('/confluence-other/download/1/a.png')

        then:
            requests.first().uri == '/confluence/confluence-other/download/1/a.png'
    }

    def 'a link that already carries the context is not given it twice'() {
        given:
            responses << 'BYTES'

        when:
            reader().download('/confluence/download/attachments/1/a.png')

        then:
            requests.first().uri == '/confluence/download/attachments/1/a.png'
    }

    def 'an attachment that is gone answers with nothing rather than failing'() {
        given:
            responses << '404:'

        expect:
            reader().download('/download/attachments/1/gone.png') == null
    }
}
