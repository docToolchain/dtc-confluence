package org.docToolchain.confluence.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Command line access to the Confluence publisher, without a build tool in the way.
 */
@Command(name = "dtc-confluence",
        description = "Publish documentation to Confluence, and check what is there.",
        mixinStandardHelpOptions = true,
        version = "dtc-confluence 0.1.0-SNAPSHOT",
        subcommands = {PublishCommand.class, VerifyCommand.class, WipeCommand.class})
public class ConfluenceCli implements Runnable {

    @Override
    public void run() {
        // No subcommand given: show the usage rather than doing something unasked.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ConfluenceCli()).execute(args));
    }
}
