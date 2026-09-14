package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceApiVersion
import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV1
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV2

abstract class AbstractConfluenceTask extends DocToolchainTask {

    ConfluenceClient confluenceClient

    AbstractConfluenceTask(ConfigObject config) {
        super(config)
        // Not read straight from the configuration: where it is unset, the version is derived
        // from the API URL rather than silently defaulting to v2, which Data Center answers with 404.
        boolean useV1Api = ConfluenceApiVersion.useV1(configService)
        if(useV1Api){
            println("Using Confluence API V1")
            this.confluenceClient = new ConfluenceClientV1(configService)
        } else {
            println("Using Confluence API V2")
            this.confluenceClient = new ConfluenceClientV2(configService)
        }
    }
}
