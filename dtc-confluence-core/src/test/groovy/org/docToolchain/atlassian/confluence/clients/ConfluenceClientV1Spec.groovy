package org.docToolchain.atlassian.confluence.clients

import groovy.json.JsonSlurper
import org.docToolchain.configuration.ConfigService
import org.docToolchain.util.TestUtils

class ConfluenceClientV1Spec extends ConfluenceClientSpec {

    @Override
    ConfluenceClient getConfluenceClient(ConfigService configService) {
        // V1 issues no request while being constructed, so it needs no stand-in client.
        return new ConfluenceClientV1(configService)
    }

    @Override
    ConfluenceClient setupConfluenceClientToFetchPagesBySpaceKey(ConfigService configService) {
        def mock = Spy(new RestClient(configService))
        mock.doRequestAndFailIfNot20x(_) >> new JsonSlurper().parse(new File("${TestUtils.TEST_RESOURCES_DIR}/asciidoc2confluence/json/apiV1/space.json"))
            >> [results: []]
        return new ConfluenceClientV1(configService, mock)
    }

    @Override
    ConfluenceClient setupConfluenceClientToFetchPagesByAncestorIdKey(ConfigService configService) {
        JsonSlurper jsonSlurper = new JsonSlurper()
        def mock = Spy(new RestClient(configService))
        mock.doRequestAndFailIfNot20x(_) >> jsonSlurper.parse(new File("${TestUtils.TEST_RESOURCES_DIR}/asciidoc2confluence/json/apiV1/ancestorId.json"))
            >> [results: []]
            >> jsonSlurper.parse(new File("${TestUtils.TEST_RESOURCES_DIR}/asciidoc2confluence/json/apiV1/ancestorId_child.json"))
            >> [results: []]
        return new ConfluenceClientV1(configService, mock)
    }

    def "the attachment Confluence holds is read out of a v1 answer"() {
        given: "v1 nests the version comment under extensions"
            def mockedResponse =  [results:[
                [id:"att178445677", type:"attachment", status:"current", title:"01_2_iso-25010-topics-DE.drawio.png",
                 macroRenderedOutput:[:], metadata:[comment:"automatically uploaded #a937de4e4d3a556d0b8886d7ecb5e0c5#", mediaType:"image/png"],
                 version:[number:7, message:"automatically uploaded #a937de4e4d3a556d0b8886d7ecb5e0c5#"],
                 extensions:[mediaType:"image/png", fileSize:"100109", comment:"automatically uploaded #a937de4e4d3a556d0b8886d7ecb5e0c5#",
                             mediaTypeDescription:"PNG Image", fileId:"e790d35e-bdc1", collectionName:"contentId-44444"],
                 _expandable:[], _links:[webui:"", self:"", download:""]]], start:0, limit:50, size:1, _links:[base:"", context:"/wiki", self:""]]

        when: "i create a ConfluenceClientV1"
            ConfigObject config = new ConfigObject()
            config.confluence = [
                api: "https://confluence.atlassian.com",
                credentials: "user:password"
            ]
            ConfigService configService = new ConfigService(config)
            ConfluenceClient confluenceClient = getConfluenceClient(configService)

        and: "i ask it what Confluence holds"
            def remote = confluenceClient.attachmentOf(mockedResponse)

        then: "the id to post a new revision to, the revision it holds, and who wrote that revision"
            remote.id() == "att178445677"
            remote.version() == 7
            ConfluenceClient.hashIn(remote.comment()) == "a937de4e4d3a556d0b8886d7ecb5e0c5"
    }

    def "a v1 answer that found no attachment is no attachment"() {
        given:
            ConfigObject config = new ConfigObject()
            config.confluence = [api: "https://confluence.atlassian.com", credentials: "user:password"]
            ConfluenceClient confluenceClient = getConfluenceClient(new ConfigService(config))

        expect: "null rather than an empty attachment, so the publisher creates one"
            confluenceClient.attachmentOf([results: []]) == null
            confluenceClient.attachmentOf(null) == null
    }
}
