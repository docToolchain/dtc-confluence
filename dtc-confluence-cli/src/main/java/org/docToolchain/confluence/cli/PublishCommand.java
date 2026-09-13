package org.docToolchain.confluence.cli;

import java.util.concurrent.Callable;

import groovy.util.ConfigObject;
import org.docToolchain.tasks.Asciidoc2ConfluenceTask;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "publish", description = "Publish the configured HTML files to Confluence.")
public class PublishCommand implements Callable<Integer> {

    @Mixin
    private ConfigurationOptions options;

    @Override
    public Integer call() throws Exception {
        ConfigObject config = options.load();
        Asciidoc2ConfluenceTask.From(config, options.docDir()).execute();
        return 0;
    }
}
