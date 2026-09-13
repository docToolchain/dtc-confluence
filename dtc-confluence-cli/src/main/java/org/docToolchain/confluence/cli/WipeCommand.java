package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import org.docToolchain.tasks.WipeConfluenceSpaceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Deletes every page in a space. Guarded by an explicit confirmation, because nothing else stands
 * between a mistyped space key and a wiped space.
 */
@Command(name = "wipe", description = "Delete every page in the configured space.")
public class WipeCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    @Option(names = "--yes-delete-every-page", required = true,
            description = "Required. Without it nothing is deleted.")
    private boolean confirmed;

    @Override
    public Integer call() throws Exception {
        if (!confirmed) {
            System.err.println("Refusing to wipe without --yes-delete-every-page.");
            return 1;
        }
        new WipeConfluenceSpaceTask(options.load()).execute();
        return 0;
    }
}
