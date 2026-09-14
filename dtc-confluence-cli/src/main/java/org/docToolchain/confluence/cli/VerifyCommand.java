package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.VerifyConfluenceApiAccessTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "verify", description = "Check that the API URL and the credentials work.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class VerifyCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    @Override
    public Integer call() throws Exception {
        new VerifyConfluenceApiAccessTask(options.load()).execute();
        return 0;
    }
}
