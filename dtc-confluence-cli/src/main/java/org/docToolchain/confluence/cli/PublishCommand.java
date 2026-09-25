package org.docToolchain.confluence.cli;

import java.util.Map;
import java.util.concurrent.Callable;

import groovy.util.ConfigObject;

import org.docToolchain.tasks.Asciidoc2ConfluenceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "publish", description = "Publish the configured HTML files to Confluence.",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class)
public class PublishCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    @Option(names = {"-n", "--dry-run"},
            description = "Say what publishing would change, and write nothing.")
    private boolean dryRun;

    @Option(names = "--move",
            description = "Move a page this publisher wrote when it hangs somewhere else, "
                    + "instead of failing on the title that is already taken.")
    private boolean moveExistingPages;

    private TaskFactory taskFactory = Asciidoc2ConfluenceTask::From;

    /** Visible for testing; see {@link TaskFactory}. */
    void useTaskFactory(TaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    @Override
    public Integer call() throws Exception {
        ConfigObject config = options.load();
        // Into the configuration rather than onto the task: the task is built through a factory
        // that hands back the base type, and a build plugin reaches the same settings.
        if (dryRun) {
            confluenceSection(config).put("dryRun", true);
        }
        if (moveExistingPages) {
            confluenceSection(config).put("moveExistingPages", true);
        }
        taskFactory.create(config, options.docDir()).execute();
        return 0;
    }

    /**
     * @return the confluence section, whatever shape it arrived in - a section written as a map
     *         literal is a plain map, one built by property assignment a ConfigObject
     */
    private static Map<Object, Object> confluenceSection(ConfigObject config) {
        if (config.get("confluence") instanceof Map<?, ?> section) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> typed = (Map<Object, Object>) section;
            config.put("confluence", typed);
            return typed;
        }
        ConfigObject created = new ConfigObject();
        config.put("confluence", created);
        return created;
    }
}
