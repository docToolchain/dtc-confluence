package org.docToolchain.confluence.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Command line access to the Confluence publisher, without a build tool in the way.
 */
@Command(name = "dtc-confluence",
        description = "Publish documentation to Confluence, and check what is there.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        subcommands = {InitCommand.class, PublishCommand.class, ExportCommand.class,
                VerifyCommand.class, WipeCommand.class})
public class ConfluenceCli implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        // No subcommand given: show the usage rather than doing something unasked. Written to the
        // command's own stream, not to System.out, so that a caller can capture it.
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ConfluenceCli()).execute(args));
    }
}
