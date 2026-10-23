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

    def 'the hash in a version comment is read only where it is one of ours'() {
        expect: '''An upload of this publisher writes an MD5 between hash marks. Carrying the hash
                   is what says "this publisher wrote the revision Confluence holds", so anything
                   else that happens to have hash marks in it must not count - a comment reading
                   "see #123#" would otherwise have authorised a write over somebody else's
                   revision.'''
            ConfluenceClient.hashIn(comment) == hash

        where:
            comment                                              || hash
            'automatically uploaded\r\n#a937de4e4d3a556d0b8886d7ecb5e0c5#' || 'a937de4e4d3a556d0b8886d7ecb5e0c5'
            '#a937de4e4d3a556d0b8886d7ecb5e0c5#'                 || 'a937de4e4d3a556d0b8886d7ecb5e0c5'
            'Manually revised; see #123#'                        || ''
            'draw.io diagram'                                    || ''
            'see #MNG-5958# and #PR-7#'                          || ''
            '#A937DE4E4D3A556D0B8886D7ECB5E0C5#'                 || ''
            '#a937de4e4d3a556d0b8886d7ecb5e0c#'                  || ''
            '#a937de4e4d3a556d0b8886d7ecb5e0c5a#'                || ''
            null                                                 || ''
            ''                                                   || ''
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

    def 'verifyCredentials accepts a Cloud user, which has no username'() {
        given: """Cloud stopped answering with a username: /user/current returns an accountId, a
                  publicName and a displayName. Required to see a username, the check refused
                  every Cloud instance - with credentials that work."""
            body = '{"type":"known","accountId":"5b10a2844c20165700ede21g",' +
                '"publicName":"gerd","displayName":"Gerd Aschemann"}'

        when:
            def user = client().verifyCredentials()

        then:
            user.accountId == '5b10a2844c20165700ede21g'
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

    def 'verifyCredentials rejects a user with nothing that identifies it'() {
        given: 'neither anonymous nor usable: no username, no accountId, no user key'
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

    def 'baseUrlFor says where Confluence is served, context path included'() {
        given: '''what an export builds a link from when it meets a link to a page it did not
                  export itself. The configured value ranges from a bare host to a full API
                  endpoint, and what a browser needs is neither.'''
            ConfigObject config = new ConfigObject()
            config.confluence = [api: api]

        expect:
            ConfluenceClient.baseUrlFor(new ConfigService(config)) == baseUrl

        where:
            api                                            || baseUrl
            'https://cwiki.apache.org/confluence'          || 'https://cwiki.apache.org/confluence'
            'https://cwiki.apache.org/confluence/rest/api' || 'https://cwiki.apache.org/confluence'
            'https://example.atlassian.net'                || 'https://example.atlassian.net/wiki'
            'http://localhost:8090/confluence'             || 'http://localhost:8090/confluence'
            'confluence.example/confluence'                || ''
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
