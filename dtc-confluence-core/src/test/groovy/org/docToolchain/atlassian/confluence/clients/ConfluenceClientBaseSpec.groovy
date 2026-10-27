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

    /** The body to answer with, or null to answer with no body at all. */
    @Shared
    String body = ''

    /** What the server declares the body to be; a login page says text/html. */
    @Shared
    String contentType = null

    /** Where a REST call is sent instead of being answered, as an SSO proxy sends it. */
    @Shared
    String redirectTo = null

    @Shared
    List<String> paths = []

    def setupSpec() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { exchange ->
            paths << exchange.requestURI.toString()
            if (redirectTo != null && exchange.requestURI.toString().contains('/rest/api')) {
                exchange.responseHeaders.set('Location', redirectTo)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return
            }
            if (contentType != null) {
                exchange.responseHeaders.set('Content-Type', contentType)
            }
            if (body == null) {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
                return
            }
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
        body = ''
        contentType = null
        redirectTo = null
    }

    private ConfluenceClient client(Map alsoConfigured = [:]) {
        ConfigObject config = new ConfigObject()
        config.confluence = [api: "http://127.0.0.1:${server.address.port}",
                             credentials: 'x'] + alsoConfigured
        return new ConfluenceClientV1(new ConfigService(config))
    }

    private String outputOf(Closure work) {
        def captured = new ByteArrayOutputStream()
        def previous = System.out
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            work()
        } finally {
            System.out = previous
        }
        return captured.toString('UTF-8')
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

    def 'verifyCredentials names the address it is about to use'() {
        given: """Three faults look alike in the output of this check - a wrong address, a wrong
                  token, and something in front of Confluence answering in its place - and the
                  configured value does not say where the call goes: confluence.api takes anything
                  from a bare host to a full endpoint, and the context path is derived from
                  it."""
            body = '{"type":"known","username":"ascheman","displayName":"Gerd Aschemann"}'

        when:
            def output = outputOf { client(bearerToken: 'a-personal-access-token').verifyCredentials() }

        then: 'the derived endpoint, printed before the answer it is about to fetch'
            output.contains("http://127.0.0.1:${server.address.port}/rest/api/user/current")
            output.indexOf('Verifying') < output.indexOf('Authenticated as')

        and: 'no part of the credentials is printed, not even a fragment of the token'
            !output.contains('a-personal-access-token')
            !output.contains('a-personal')
    }

    def 'verifyCredentials blames the address, not the credentials, for a login page'() {
        given: """What a customer instance behind an SSO proxy answers: the REST call is redirected
                  to a login page, the client follows it, and an HTML document arrives with status
                  200. Reported as an anonymous user, that sent the reader to the token while the
                  request had never reached a REST endpoint."""
            redirectTo = '/login.action?os_destination=%2Frest%2Fapi'
            contentType = 'text/html;charset=UTF-8'
            body = '<html><head><title>Log in</title></head><body>Please log in</body></html>'

        when:
            client().verifyCredentials()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('did not answer as an API')
            e.message.contains("http://127.0.0.1:${server.address.port}/rest/api/user/current")

        and: 'the evidence that something other than the API answered'
            e.message.contains('200')
            e.message.contains('text/html')

        and: 'the two things that cause it, and the one thing that does not'
            e.message.contains('context path')
            e.message.contains('SSO proxy')
            e.message.contains('credentials are not implicated')
            !e.message.contains('anonymous')
            !e.message.contains('bearerToken')
    }

    def 'verifyCredentials reports a web page served without a redirect the same way'() {
        given: 'a gateway that answers in place of Confluence need not redirect first'
            contentType = 'text/html'
            body = '<html><body>Not Confluence</body></html>'

        when:
            client().verifyCredentials()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('did not answer as an API')
            !e.message.contains('anonymous')
    }

    def 'verifyCredentials does not blame the credentials for an answer with no content'() {
        given: """An answer carrying nothing parsed to nothing, and nothing was read as an
                  anonymous user - the same misdiagnosis by another route. 204 is what reaches
                  this: the client hands over a response without an entity at all."""
            status = 204
            body = null

        when:
            client().verifyCredentials()

        then:
            def e = thrown(IllegalStateException)
            e.message.contains('did not answer as an API')
            !e.message.contains('bearerToken')
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
            // A Data Center served at the root, which is what a page URL of
            // https://wiki.example/pages/... says. The host says it is not Cloud, so a URL
            // without a path is taken at the root rather than given Cloud's /wiki.
            'https://wiki.example/rest/api'                || 'https://wiki.example'
            'https://wiki.example/rest/api/'               || 'https://wiki.example'
            'https://wiki.example'                         || 'https://wiki.example'
            'https://wiki.example/'                        || 'https://wiki.example'
            // Cloud without a path is the one place /wiki belongs, and the host is what says so.
            'https://example.atlassian.net/'               || 'https://example.atlassian.net/wiki'
            'https://EXAMPLE.ATLASSIAN.NET'                || 'https://EXAMPLE.ATLASSIAN.NET/wiki'
    }

    def 'retrievePageIdByName passes the title and space through to the query'() {
        given:
            body = '{"results":[]}'

        when:
            client().retrievePageIdByName('Some Page', 'SPACE')

        then: 'the title is percent-encoded, not form-encoded, so a space stays a space'
            paths.first() == '/rest/api/content?title=Some%20Page&spaceKey=SPACE'
    }

    def 'the names of a page attachments are read out of the listing'() {
        given: 'both APIs call an attachment name its title, so one reading serves both'
            body = '{"results":[{"id":"1","title":"my image.png"},{"id":"2","title":"plan.pdf"}]}'

        expect:
            client().attachmentNames('4711') == ['my image.png', 'plan.pdf']
    }

    def 'a page with no attachments answers no names'() {
        given:
            body = '{"results":[]}'

        expect:
            client().attachmentNames('4711') == []
    }

    def 'an answer that lists nothing is no names rather than a failure'() {
        given: 'a page that is gone answers 404, and a name that matches nothing is the fallback'
            status = 404
            body = 'not found'

        expect:
            client().attachmentNames('4711') == []
    }
}
