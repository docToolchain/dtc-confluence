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

    def 'a missing reason is reported as absent rather than as null'() {
        when:
            def exception = new RequestFailedException(response(HttpStatus.SC_BAD_REQUEST, 'Bad Request'), null)

        then:
            exception.message.contains('reason: <none>')
    }
}
