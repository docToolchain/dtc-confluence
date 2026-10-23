package org.docToolchain.http

import org.apache.hc.core5.http.HttpResponse
import org.apache.hc.core5.http.HttpStatus
import spock.lang.Specification
import spock.lang.Unroll

class RequestFailedExceptionSpec extends Specification {

    private HttpResponse response(int code, String reason) {
        Stub(HttpResponse) {
            getCode() >> code
            getReasonPhrase() >> reason
        }
    }

    @Unroll
    def 'a #code response suggests: #expectedHint'() {
        when:
            def exception = new RequestFailedException(response(code, 'some reason'), null)

        then: 'the hint tells the user what to look at'
            exception.message.contains(expectedHint)

        and: 'the status line is reported so the failure can be identified'
            exception.message.contains("${code} some reason")

        where:
            code                             || expectedHint
            HttpStatus.SC_BAD_REQUEST        || 'please check your config file or passed parameters'
            HttpStatus.SC_UNAUTHORIZED       || 'please check your credentials'
            HttpStatus.SC_TOO_MANY_REQUESTS  || 'decrease the rate limit'
            HttpStatus.SC_NOT_FOUND          || 'please open an issue at'
            HttpStatus.SC_INTERNAL_SERVER_ERROR || 'please open an issue at'
    }

    def 'a missing response does not throw from inside the exception constructor'() {
        when: 'no response was ever received'
            def exception = new RequestFailedException(null, new IOException('connection refused'))

        then: 'constructing the exception succeeds rather than masking the original failure'
            noExceptionThrown()

        and: 'and it points at the two things that can cause it'
            exception.message.contains('please check the configured API URL and network access')
            exception.message.contains('response: <none>')
    }

    def 'the underlying reason is carried, as message and as cause'() {
        given:
            def cause = new IOException('socket closed')

        when:
            def exception = new RequestFailedException(response(HttpStatus.SC_UNAUTHORIZED, 'Unauthorized'), cause)

        then:
            exception.message.contains('reason: socket closed')
            exception.cause.is(cause)
    }

    def 'what the server said with the rejection is the reason'() {
        given: '''exactly what cwiki answered a publish of a document with no title, which this
                  exception used to report as "reason: <none>" with a hint to check the config'''
            def said = '{"statusCode":400,"data":{"authorized":true,"valid":false,' +
                    '"errors":[{"message":{"key":"You must specify a title for new content."}}]},' +
                    '"reason":"Bad Request"}'

        when:
            def exception = new RequestFailedException(
                    response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'), said, null)

        then: 'the one sentence that says what is wrong is in the message'
            exception.message.contains('You must specify a title for new content.')
    }

    def 'a rejection the server did not explain still says so'() {
        expect:
            new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'),
                    body, null).message.contains('reason: <none>')

        where: 'no body at all, an empty one, and one that is only whitespace'
            body << [null, '', '   \n  ']
    }

    def 'an exception is the reason even when the server said something too'() {
        given:
            def cause = new IOException('socket closed')

        when:
            def exception = new RequestFailedException(
                    response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'), 'server chatter', cause)

        then: 'the exception is nearer the cause than the body is'
            exception.message.contains('reason: socket closed')
            !exception.message.contains('server chatter')
    }

    def 'a body spread over many lines is reported on one'() {
        when:
            def exception = new RequestFailedException(
                    response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'),
                    '<html>\n  <body>\n    Bad Request\n  </body>\n</html>', null)

        then: 'so that it does not push the lines around it out of sight'
            exception.message.contains('reason: <html> <body> Bad Request </body> </html>,')
    }

    def 'a body wrapped in whitespace is reported without it'() {
        when:
            def exception = new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'),
                    '\n  {"message":"no"}  \n', null)

        then: 'so that the line does not begin with the space the server happened to send'
            exception.message.contains('reason: {"message":"no"},')
    }

    def 'a body of exactly the limit is reported whole'() {
        when: 'one character more and it would be cut, one fewer and nothing is being tested'
            def exception = new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'),
                    'y' * RequestFailedException.BODY_LIMIT, null)

        then:
            exception.message.contains('reason: ' + 'y' * RequestFailedException.BODY_LIMIT + ',')

        and: 'and is not labelled as cut off, because it was not'
            !exception.message.contains('cut off')
    }

    def 'a body carrying control characters cannot break the line it is printed on'() {
        given: '''this message is printed as a stack trace on stderr, and a response body is
                  written by whatever answered the request'''
            def nasty = 'a\u001b[2Jb\u0085c d\u0000e'

        when:
            def exception = new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'),
                    nasty, null)

        then: '''each run of them becomes one space - what is left of an escape sequence is
                 ordinary text, which is the point: it can no longer take effect'''
            exception.message.contains('reason: a [2Jb c d e,')

        and:
            !exception.message.contains('\u001b')
            !exception.message.contains('\u0085')
            !exception.message.contains(' ')
            !exception.message.contains('\u0000')
    }

    def 'a body too long to print is cut off rather than printed whole'() {
        given: 'an error page far longer than anything the message has room for'
            def huge = 'x' * (5 * RequestFailedException.BODY_LIMIT)

        when:
            def exception = new RequestFailedException(
                    response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'), huge, null)

        then: 'enough of it to recognise, and an honest note that there was more'
            exception.message.contains('x' * RequestFailedException.BODY_LIMIT + '... (cut off)')

        and: 'and the hint that follows it is still readable'
            exception.message.contains('possible solution:')

        and:
            exception.message.length() < huge.length()
    }

    def 'a missing reason is reported as absent rather than as null'() {
        when:
            def exception = new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'), null)

        then:
            exception.message.contains('reason: <none>')
    }
}
