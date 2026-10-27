package org.docToolchain.http;

/**
 * Raised where a request the server answered with 2xx carried something other than JSON.
 *
 * <p>Measured against a customer instance with an SSO proxy in front of Confluence: a REST call
 * that does not reach Confluence is answered {@code 302} to {@code /login.action}, the client
 * follows the redirect, and what comes back is an HTML document with status {@code 200}. Read as
 * JSON, that raised a bare {@code JsonException} - "Unable to determine the current character ...
 * read is '&lt;'" - with the whole page appended to it, naming neither the status, nor the type
 * the server declared, nor the one thing a reader needs to know: that the request never reached a
 * REST endpoint, so the credentials are beside the point.</p>
 *
 * <p>An empty body raises its own exception from the parser ({@code IllegalArgumentException},
 * "Text must not be null or empty") and is reported here as well, because it says the same thing:
 * whatever answered was not the API.</p>
 */
public class NonJsonResponseException extends RuntimeException {

    private static final String UNDECLARED = "no content type";

    /**
     * @param contentType what the server declared the body to be, or {@code null} where it
     *                    declared nothing - a login page says {@code text/html}, which is half
     *                    the diagnosis on its own
     * @param body        the body as it was read, flattened and bounded for printing the same way
     *                    a rejection's body is
     */
    public NonJsonResponseException(int statusCode, String contentType, String body,
                                    Exception reason) {
        super("the endpoint answered " + statusCode + " ("
                + (contentType == null || contentType.isBlank() ? UNDECLARED : contentType)
                + ") with " + describe(body), reason);
    }

    private static String describe(String body) {
        return body == null || body.isBlank()
                ? "an empty body"
                : "a body that is not JSON: " + RequestFailedException.serverSaid(body);
    }
}
