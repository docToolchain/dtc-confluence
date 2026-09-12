package org.docToolchain.http;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a Confluence REST call did not succeed.
 *
 * <p>The message carries the status line, the underlying reason and a hint at what to check,
 * because the three together are what a user needs to act on a failing publish.</p>
 */
public class RequestFailedException extends RuntimeException {

    private static final String ISSUE_TRACKER = "https://github.com/docToolchain/dtc-confluence/issues";
    private static final String NONE = "<none>";

    public RequestFailedException(HttpResponse response, Exception reason) {
        super(buildMessage(response, reason), reason);
    }

    private static String buildMessage(HttpResponse response, Exception reason) {
        String responseLog = response != null ? statusLine(response) : NONE;
        String reasonLog = reason != null ? reason.getMessage() : NONE;

        return "something went wrong - request failed ("
                + "\nresponse: " + responseLog + ", "
                + "\nreason: " + reasonLog + ", "
                + "\npossible solution: " + possibleSolution(response)
                + ")";
    }

    private static String possibleSolution(HttpResponse response) {
        if (response == null) {
            return "no response was received - please check the configured API URL and network access";
        }
        return switch (response.getCode()) {
            case HttpStatus.SC_BAD_REQUEST ->
                    "please check your config file or passed parameters";
            case HttpStatus.SC_UNAUTHORIZED ->
                    "please check your credentials in config file or passed parameters";
            case HttpStatus.SC_TOO_MANY_REQUESTS ->
                    "please check if you need to decrease the rate limit in your config file";
            default ->
                    "please check your config. If you are sure that everything is correct, "
                            + "please open an issue at " + ISSUE_TRACKER;
        };
    }

    private static String statusLine(HttpResponse response) {
        return response.getCode() + " " + response.getReasonPhrase();
    }
}
