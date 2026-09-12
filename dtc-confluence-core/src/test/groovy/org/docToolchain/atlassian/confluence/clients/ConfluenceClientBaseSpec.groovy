package org.docToolchain.atlassian.confluence.clients

import com.sun.net.httpserver.HttpServer
import org.docToolchain.configuration.ConfigService
import org.docToolchain.http.RequestFailedException
import spock.lang.Shared
import spock.lang.Specification

/**
 * Covers the behaviour that lives in the abstract base class, exercised through V1 because the
 * base cannot be instantiated. Runs against a JDK HttpServer, so the request actually happens.
 */
class ConfluenceClientBaseSpec extends Specification {

    @Shared
    HttpServer server

    @Shared
    int status = 200

    @Shared
    String body = ''

    @Shared
    List<String> paths = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            paths << exchange.requestURI.toString()
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
        paths.clear()
        status = 200
    }

    private ConfluenceClient client() {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}", credentials: 'x']
        return new ConfluenceClientV1(new ConfigService(config))
    }

    def 'verifyCredentials accepts a resolved user'() {
        given:
            body = '{"type":"known","username":"ascheman","displayName":"Gerd Aschemann"}'

        when:
            def user = client().verifyCredentials()

        then:
            user.username == 'ascheman'

        and: 'it asks the endpoint that reports who the credentials belong to'
            paths.first().endsWith('/rest/api/user/current')
    }

    def 'verifyCredentials rejects an anonymous user'() {
        given: """Data Center answers an invalid token with 200 and an anonymous user rather than
                  401, so the status code alone proves nothing."""
            body = '{"type":"anonymous"}'

        when:
            client().verifyCredentials()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('anonymous')
            e.message.contains('bearerToken')
    }

    def 'verifyCredentials rejects a user without a name'() {
        given: 'a body that is neither anonymous nor usable'
            body = '{"type":"known"}'

        when:
            client().verifyCredentials()

        then:
            thrown(IllegalStateException)
    }

    def 'verifyCredentials surfaces a rejected request'() {
        given:
            status = 401
            body = 'nope'

        when:
            client().verifyCredentials()

        then: 'an instance that does answer 401 is reported as such'
            thrown(RequestFailedException)
    }

    def 'retrieveFullPageById answers an empty map when there is no such page'() {
        given:
            status = 404
            body = 'not found'

        expect: 'callers can treat the result as a page-shaped thing either way'
            client().retrieveFullPageById('4711') == [:]
    }

    def 'retrieveFullPageById returns the page when there is one'() {
        given:
            body = '{"id":"4711","title":"Some Page"}'

        expect:
            client().retrieveFullPageById('4711').title == 'Some Page'
    }

    def 'retrievePageIdByName passes the title and space through to the query'() {
        given:
            body = '{"results":[]}'

        when:
            client().retrievePageIdByName('Some Page', 'SPACE')

        then: 'the title is percent-encoded, not form-encoded, so a space stays a space'
            paths.first() == '/wiki/rest/api/content?title=Some%20Page&spaceKey=SPACE'
    }
}
