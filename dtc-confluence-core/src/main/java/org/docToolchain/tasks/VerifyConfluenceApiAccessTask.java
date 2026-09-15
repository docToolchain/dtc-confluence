package org.docToolchain.tasks;

import java.util.logging.Logger;

import groovy.util.ConfigObject;

/**
 * Checks that the configured URL and credentials reach Confluence, and says who they belong to.
 */
public class VerifyConfluenceApiAccessTask extends AbstractConfluenceTask {

    private static final Logger LOGGER =
            Logger.getLogger(VerifyConfluenceApiAccessTask.class.getName());

    public VerifyConfluenceApiAccessTask(ConfigObject config) {
        super(config);
    }

    @Override
    public void execute() {
        LOGGER.info("Verifying confluence API access...");
        confluenceClient.verifyCredentials();
    }
}
