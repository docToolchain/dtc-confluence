package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import org.docToolchain.configuration.ConfigService
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * The upload request as it goes on the wire, against a real server, because the defect this pins
 * was in the headers the HTTP client writes rather than in anything this project computes.
 */
class AttachmentUploadRequestSpec extends Specification {

    HttpServer server
    String contentType
    byte[] sent

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            contentType = exchange.requestHeaders.getFirst('Content-Type')
            sent = exchange.requestBody.bytes
            byte[] payload = '{"results":[]}'.bytes
            exchange.sendResponseHeaders(200, payload.length)
            exchange.responseBody.withStream { it.write(payload) }
        }
        server.start()
    }

    def cleanup() {
        server?.stop(0)
    }

    def 'the request declares no charset, because Confluence believes the declaration'() {
        when:
            upload('Übersicht.png')

        then: '''The entity would say charset=ISO-8859-1 whatever the builder is told, and
                 Confluence then read the correct UTF-8 bytes as Latin-1 and stored a name
                 beginning with an A-tilde. A browser sends no charset, and nor does this.'''
            contentType.startsWith('multipart/form-data; boundary=')
            !contentType.toLowerCase().contains('charset')
    }

    def 'an attachment name reaches the wire as its own bytes'() {
        when:
            upload(name)

        then:
            new String(sent, StandardCharsets.UTF_8).contains("filename=\"$name\"")

        where: 'a space, an umlaut, and both - none of which the publisher may rewrite'
            name << ['my image.png', 'Übersicht.png', 'Plan ü.png']
    }

    private void upload(String fileName) {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}", credentials: 'x']
        new ConfluenceClientV1(new ConfigService(config))
                .createAttachment('1000', new ByteArrayInputStream('a picture'.bytes), fileName,
                        'automatically uploaded', '0123456789abcdef0123456789abcdef')
    }
}
