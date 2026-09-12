package org.docToolchain.http

import com.sun.net.httpserver.HttpServer
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import spock.lang.Shared
import spock.lang.Specification

/**
 * Exercises the client against a real socket rather than a mock, because the behaviour under
 * test - which headers end up on the wire - is only observable there.
 *
 * The server is the one in the JDK, so this costs no dependency.
 */
class BasicRestClientSpec extends Specification {

    static class TestClient extends BasicRestClient {}

    @Shared
    HttpServer server

    @Shared
    List<Map<String, String>> received = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            received << exchange.requestHeaders.collectEntries { k, v -> [(k.toLowerCase()): v.first()] }
            byte[] body = 'served'.bytes
            exchange.sendResponseHeaders(200, body.length)
            exchange.responseBody.withStream { it.write(body) }
        }
        server.start()
    }

    def cleanupSpec() {
        // HttpServer.stop takes a grace period in seconds; zero means now.
        server?.stop(0)
    }

    def setup() {
        received.clear()
    }

    private HttpHost target() {
        new HttpHost('http', '127.0.0.1', server.address.port)
    }

    private static HttpClientResponseHandler<String> echoBody() {
        { response -> response.entity.content.text } as HttpClientResponseHandler<String>
    }

    def 'the response handler result is returned, wrapped'() {
        when:
            def result = new TestClient().doRequest(target(), new HttpGet('/some/path'), echoBody())

        then:
            result.present
            result.get() == 'served'
    }

    def 'every request is stamped with a user agent'() {
        when:
            new TestClient().doRequest(target(), new HttpGet('/some/path'), echoBody())

        then:
            received.first()['user-agent'].startsWith('docToolchain_v')
    }

    def 'the Host header is derived from the request URI when absent'() {
        when:
            new TestClient().doRequest(target(), new HttpGet('http://example.invalid/some/path'), echoBody())

        then: 'the interceptor filled it in from the URI, not from the target host'
            received.first()['host'] == 'example.invalid'
    }

    def 'an explicitly set Host header survives'() {
        given:
            def request = new HttpGet('http://example.invalid/some/path')
            request.setHeader(HttpHeaders.HOST, 'chosen.example')

        when:
            new TestClient().doRequest(target(), request, echoBody())

        then:
            received.first()['host'] == 'chosen.example'
    }

    def 'a connection failure is reported as a runtime exception carrying the cause'() {
        given: 'a port nothing listens on'
            def unused = new ServerSocket(0).withCloseable { it.localPort }

        when:
            new TestClient().doRequest(new HttpHost('http', '127.0.0.1', unused), new HttpGet('/'), echoBody())

        then:
            def e = thrown(RuntimeException)
            e.cause instanceof IOException
    }
}
