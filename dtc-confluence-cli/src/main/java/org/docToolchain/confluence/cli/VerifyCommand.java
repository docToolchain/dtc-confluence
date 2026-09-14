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

    private TaskFactory taskFactory = (config, docDir) -> new VerifyConfluenceApiAccessTask(config);

    /** Visible for testing; see {@link TaskFactory}. */
    void useTaskFactory(TaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    @Override
    public Integer call() throws Exception {
        taskFactory.create(options.load(), options.docDir()).execute();
        return 0;
    }
}
