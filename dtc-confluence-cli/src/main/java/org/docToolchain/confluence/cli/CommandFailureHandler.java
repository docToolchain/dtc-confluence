package org.docToolchain.confluence.cli;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Says why a command failed, in the words the failure was written in.
 *
 * <p>Without this picocli prints the exception the way Java does: the class name, the message, and
 * fifteen frames of this program's own internals. A message such as the one the credentials check
 * writes - which names the address it tried and says that the credentials are not what went wrong
 * - is then buried in a stack trace, and the reader takes from it that something crashed rather
 * than what to change.</p>
 *
 * <p>The causes are printed too, because the sentence that explains a failure is often one level
 * down: the client says the endpoint did not answer as an API, and the layer below says what it
 * actually sent back.</p>
 *
 * <p>A failure with nothing to say in words still gets its stack trace. Something like a
 * {@code NullPointerException} carries no message, and printing only its type would leave the
 * reader with less than Java gave them - the trace is then the only evidence there is, and this
 * is a defect to report rather than a situation to correct.</p>
 */
final class CommandFailureHandler implements IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception failure, CommandLine command,
                                        ParseResult parseResult) {
        PrintWriter err = command.getErr();
        List<String> said = spokenCausesOf(failure);
        if (said.isEmpty()) {
            failure.printStackTrace(err);
        } else {
            said.forEach(err::println);
        }
        err.flush();
        return command.getExitCodeExceptionMapper() != null
                ? command.getExitCodeExceptionMapper().getExitCode(failure)
                : command.getCommandSpec().exitCodeOnExecutionException();
    }

    /**
     * @return what the failure and the ones behind it say, outermost first, without the ones that
     *         say nothing and without repeating a message a wrapper carried up unchanged
     */
    private static List<String> spokenCausesOf(Throwable failure) {
        List<String> said = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            String trimmed = message.strip();
            if (said.isEmpty()) {
                said.add(trimmed);
            } else if (!said.get(said.size() - 1).contains(trimmed)) {
                said.add("  caused by: " + trimmed);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return said;
    }
}
