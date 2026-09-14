package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.Asciidoc2ConfluenceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "publish", description = "Publish the configured HTML files to Confluence.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class PublishCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    private TaskFactory taskFactory = Asciidoc2ConfluenceTask::From;

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
