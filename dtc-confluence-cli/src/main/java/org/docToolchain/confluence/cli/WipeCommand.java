package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.WipeConfluenceSpaceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Deletes every page in a space.
 *
 * <p>The confirmation is a required option, so the command line refuses the call before anything
 * here runs. Nothing else stands between a mistyped space key and an emptied space.</p>
 */
@Command(name = "wipe", description = "Delete every page in the configured space.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class WipeCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    @Option(names = "--yes-delete-every-page", required = true,
            description = "Required. Without it nothing is deleted.")
    private boolean confirmed;

    private TaskFactory taskFactory = (config, docDir) -> new WipeConfluenceSpaceTask(config);

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
