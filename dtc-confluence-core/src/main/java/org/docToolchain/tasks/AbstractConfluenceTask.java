package org.docToolchain.tasks;

import groovy.util.ConfigObject;
import org.docToolchain.atlassian.confluence.clients.ConfluenceApiVersion;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClient;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV1;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV2;

/**
 * A task that talks to Confluence, with the client already chosen.
 */
public abstract class AbstractConfluenceTask extends DocToolchainTask {

    protected ConfluenceClient confluenceClient;

    protected AbstractConfluenceTask(ConfigObject config) {
        super(config);
        // Not read straight from the configuration: where it is unset, the version is derived from
        // the API URL rather than silently defaulting to v2, which Data Center answers with 404.
        if (ConfluenceApiVersion.useV1(configService)) {
            System.out.println("Using Confluence API V1");
            this.confluenceClient = new ConfluenceClientV1(configService);
        } else {
            System.out.println("Using Confluence API V2");
            this.confluenceClient = new ConfluenceClientV2(configService);
        }
    }

    public ConfluenceClient getConfluenceClient() {
        return confluenceClient;
    }

    /**
     * Replaces the client, which is how a test puts a recorder in its place.
     */
    public void setConfluenceClient(ConfluenceClient confluenceClient) {
        this.confluenceClient = confluenceClient;
    }
}
