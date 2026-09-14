package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.WipeConfluenceSpaceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Deletes every page in a space.
 *
 * <p>Nothing else stands between a mistyped space key and an emptied space, so the confirmation is
 * both required and read. Required alone is not enough: picocli accepts
 * {@code --yes-delete-every-page=false}, which satisfies the requirement while saying no.</p>
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
        if (!confirmed) {
            System.err.println("Refusing to wipe: --yes-delete-every-page was given as false.");
            return 1;
        }
        taskFactory.create(options.load(), options.docDir()).execute();
        return 0;
    }
}
