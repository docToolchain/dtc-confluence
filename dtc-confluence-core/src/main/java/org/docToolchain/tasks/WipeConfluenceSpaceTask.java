package org.docToolchain.tasks;

import java.util.logging.Logger;

import groovy.util.ConfigObject;
import org.docToolchain.atlassian.confluence.ConfluenceService;

/**
 * Deletes every page in the configured space.
 */
public class WipeConfluenceSpaceTask extends AbstractConfluenceTask {

    private static final Logger LOGGER =
            Logger.getLogger(WipeConfluenceSpaceTask.class.getName());

    public WipeConfluenceSpaceTask(ConfigObject config) {
        super(config);
    }

    @Override
    public void execute() {
        LOGGER.warning("Wiping Confluence space...");
        new ConfluenceService(configService).wipeConfluenceSpace(confluenceClient);
    }
}
