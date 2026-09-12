package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.docToolchain.configuration.ConfigService
import org.docToolchain.http.RequestFailedException
import spock.lang.Shared
import spock.lang.Specification

/**
 * Runs against a real socket, because what is under test is what goes onto the wire and what
 * comes back off it. The server is the one in the JDK, so this costs no dependency.
 */
class RestClientSpec extends Specification {

    @Shared
    HttpServer server

    @Shared
    int status = 200

    @Shared
    String body = '{"id":"42","title":"Some Page"}'

    @Shared
    List<Map<String, String>> received = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            received << exchange.requestHeaders.collectEntries { k, v -> [(k.toLowerCase()): v.first()] }
            byte[] payload = body.bytes
            exchange.sendResponseHeaders(status, payload.length)
            exchange.responseBody.withStream { it.write(payload) }
        }
        server.start()
    }

    def cleanupSpec() {
        server?.stop(0)
    }

    def setup() {
        received.clear()
        status = 200
        body = '{"id":"42","title":"Some Page"}'
    }

    private ConfigService configService(Map confluence) {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}"] + confluence
        return new ConfigService(config)
    }

    def 'the target host comes from the configured API URL'() {
        when:
            def client = new RestClient(configService([credentials: 'dXNlcjpwYXNz']))

        then:
            client.targetHost.hostName == '127.0.0.1'
            client.targetHost.port == server.address.port
            client.targetHost.schemeName == 'http'
    }

    def 'a bearer token is preferred over basic credentials'() {
        when:
            def client = new RestClient(configService([bearerToken: 'abc123', credentials: 'ignored']))

        then:
            def auth = client.headers.find { it.name == 'Authorization' }
            auth.value == 'Bearer abc123'

        and: 'the cross-site request token Confluence expects is always present'
            client.headers.any { it.name == 'X-Atlassian-Token' && it.value == 'no-check' }
    }

    def 'without a bearer token the basic credentials are used'() {
        when:
            def client = new RestClient(configService([credentials: 'dXNlcjpwYXNz']))

        then:
            client.headers.find { it.name == 'Authorization' }.value == 'Basic dXNlcjpwYXNz'
            !client.headers.any { it.name == 'keyid' }
    }

    def 'an API gateway key is sent alongside basic credentials'() {
        when:
            def client = new RestClient(configService([credentials: 'dXNlcjpwYXNz', apikey: 'gw-key']))

        then:
            client.headers.find { it.name == 'keyid' }.value == 'gw-key'
    }

    def 'no proxy is configured unless one is given'() {
        expect:
            new RestClient(configService([credentials: 'x'])).proxyHost == null
    }

    def 'a configured proxy is applied, defaulting to http'() {
        when:
            def client = new RestClient(configService([credentials: 'x', proxy: [host: 'proxy.example', port: 8080]]))

        then:
            client.proxyHost.hostName == 'proxy.example'
            client.proxyHost.port == 8080
            client.proxyHost.schemeName == 'http'
    }

    def 'a proxy scheme is honoured when given'() {
        when:
            def client = new RestClient(configService([credentials: 'x',
                proxy: [host: 'proxy.example', port: 8443, schema: 'https']]))

        then:
            client.proxyHost.schemeName == 'https'
    }

    def 'a successful response is parsed as JSON'() {
        when:
            def result = new RestClient(configService([credentials: 'x']))
                .doRequestAndFailIfNot20x(new HttpGet('/rest/api/content/42'))

        then:
            result.id == '42'
            result.title == 'Some Page'

        and: 'the configured headers reached the server'
            received.first()['authorization'] == 'Basic x'
            received.first()['x-atlassian-token'] == 'no-check'
    }

    def 'doRequestAndFailIfNot20x raises on a rejected request'() {
        given:
            status = 404
            body = 'not found'

        when:
            new RestClient(configService([credentials: 'x'])).doRequestAndFailIfNot20x(new HttpGet('/missing'))

        then:
            def e = thrown(RequestFailedException)
            e.message.contains('404')
    }

    def 'doRequestAndReturnOrNull answers null for a missing page'() {
        given:
            status = 404
            body = 'not found'

        expect: 'the caller reads this as "the page does not exist yet"'
            new RestClient(configService([credentials: 'x'])).doRequestAndReturnOrNull(new HttpGet('/missing')) == null
    }

    def 'doRequestAndReturnOrNull raises on a server error rather than reporting a missing page'() {
        given: 'a transient server failure'
            status = 503
            body = 'service unavailable'

        when:
            new RestClient(configService([credentials: 'x'])).doRequestAndReturnOrNull(new HttpGet('/x'))

        then: """A server error is not an answer about whether the page exists. Reporting it as
                 null would make a publish create a duplicate page instead of failing."""
            def e = thrown(RequestFailedException)
            e.message.contains('503')
    }

    def 'doRequestAndFailIfNot20x reports the status rather than a closed stream'() {
        given:
            status = 500
            body = 'boom'

        when:
            new RestClient(configService([credentials: 'x'])).doRequestAndFailIfNot20x(new HttpGet('/x'))

        then:
            def e = thrown(RequestFailedException)
            e.message.contains('500')
    }
}
