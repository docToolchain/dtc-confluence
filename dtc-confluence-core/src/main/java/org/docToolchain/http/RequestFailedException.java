package org.docToolchain.http;

import java.util.regex.Pattern;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a Confluence REST call did not succeed.
 *
 * <p>The message carries the status line, the underlying reason and a hint at what to check,
 * because the three together are what a user needs to act on a failing publish.</p>
 *
 * <p>Where no exception caused the rejection, the reason is what the server said in the response
 * body. Confluence fills it with a sentence that names the problem - a publish of a document
 * without a title was answered {@code 400} and
 * {@code "You must specify a title for new content."} while this exception reported
 * {@code reason: <none>} and a hint to check the configuration, which was not where the problem
 * was.</p>
 */
public class RequestFailedException extends RuntimeException {

    private static final String ISSUE_TRACKER = "https://github.com/docToolchain/dtc-confluence/issues";
    private static final String NONE = "<none>";

    /**
     * How much of the response body the message carries.
     *
     * <p>Enough for Confluence's own JSON, which names the problem in its first few hundred
     * characters. An error page served by something in between is a different matter: the first
     * thousand characters of an HTML document are its head, so the limit bounds what is printed
     * without bringing the sentence into view. Public because it is also how much the reader of
     * a rejection needs to read off the wire.</p>
     */
    public static final int BODY_LIMIT = 1000;

    /**
     * Everything that must not reach a terminal on its own line, or at all.
     *
     * <p>Not {@code \\s+}: that is the six ASCII whitespace characters, and a response body is
     * written by whatever answered the request. A body carrying {@code U+0085}, {@code U+2028} or
     * an escape sequence would otherwise break the line it is printed on, or clear the screen -
     * this message ends up in a stack trace on stderr. {@code \\p{C}} covers the control
     * characters, {@code \\p{Z}} the separators the ASCII class misses.</p>
     */
    private static final Pattern UNPRINTABLE_RUN = Pattern.compile("[\\p{C}\\p{Z}\\s]+");

    private static final String CUT_OFF = "... (cut off)";

    public RequestFailedException(HttpResponse response, Exception reason) {
        this(response, null, reason);
    }

    /**
     * @param body what the server sent with the rejection, or {@code null} where it sent nothing
     *             or the body could not be read
     */
    public RequestFailedException(HttpResponse response, String body, Exception reason) {
        super(buildMessage(response, body, reason), reason);
    }

    private static String buildMessage(HttpResponse response, String body, Exception reason) {
        String responseLog = response != null ? statusLine(response) : NONE;
        String reasonLog = reason != null ? reason.getMessage() : serverSaid(body);

        return "something went wrong - request failed ("
                + "\nresponse: " + responseLog + ", "
                + "\nreason: " + reasonLog + ", "
                + "\npossible solution: " + possibleSolution(response)
                + ")";
    }

    /**
     * What the server said, as one printable line of at most {@link #BODY_LIMIT} characters plus
     * the note that says it was cut off.
     *
     * <p>One line because a message spread over the twenty lines of an HTML error page is harder
     * to read than one that is cut off, and because the lines around it are single lines too.</p>
     */
    private static String serverSaid(String body) {
        if (body == null || body.isBlank()) {
            return NONE;
        }
        String oneLine = UNPRINTABLE_RUN.matcher(body).replaceAll(" ").trim();
        return oneLine.length() <= BODY_LIMIT
                ? oneLine
                : oneLine.substring(0, BODY_LIMIT) + CUT_OFF;
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
