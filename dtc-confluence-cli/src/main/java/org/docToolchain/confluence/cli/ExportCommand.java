package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.ExportConfluenceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "export",
        description = "Export a Confluence page and everything below it to AsciiDoc. Needs pandoc.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class ExportCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    private TaskFactory taskFactory = ExportConfluenceTask::From;

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
