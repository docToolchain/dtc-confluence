package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient
import org.docToolchain.util.TestUtils
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * States what the publisher takes as its input: which files a configured folder stands for, and
 * how often it asks Confluence what is already there.
 */
class PublisherInputSpec extends Specification {

    private static final String RESOURCES = "${TestUtils.TEST_RESOURCES_DIR}/publisher"

    @TempDir
    Path docs

    def 'a folder of documents names each file below it, and keeps it where it is'() {
        given: """A folder written without a trailing slash used to run into the file name -
                  'html5' + 'index.html' - and two pages of the same name in different subfolders
                  became one."""
            Files.createDirectories(docs.resolve('html5/chapter'))
            Files.writeString(docs.resolve('html5/index.html'), '<html></html>')
            Files.writeString(docs.resolve('html5/chapter/index.html'), '<html></html>')

            def config = new ConfigObject()
            config.confluence.api = 'https://confluence.example/rest/api/'
            config.confluence.credentials = 'x'
            config.confluence.spaceKey = 'SPACE'
            config.confluence.input = []
            config.confluence.inputHtmlFolder = 'html5'

        when:
            def inputs = new Asciidoc2ConfluenceTask(config, docs.toString()).inputsFromFolder()

        then:
            inputs*.file.sort() == ['html5/chapter/index.html', 'html5/index.html']
    }

    def 'the pages already there are asked for once per space'() {
        given: """Asked once per page, a document of forty sections would issue forty identical
                  listings; asked once for all spaces, an input for another space would be
                  answered from the wrong one."""
            def config = new ConfigObject()
            config.docDir = RESOURCES
            config.confluence.api = 'https://confluence.example/rest/api/'
            config.confluence.credentials = 'x'
            config.confluence.useV1Api = true
            config.confluence.spaceKey = 'SPACE'
            config.confluence.input = [[file: 'smoke-input.html'],
                                       [file: 'smoke-input.html'],
                                       [file: 'smoke-input.html', spaceKey: 'OTHER']]

            def client = Mock(ConfluenceClient)
            client.retrievePageIdByName(_, _) >> null
            client.retrieveFullPageById(_) >> [:]
            client.createPage(_, _, _, _, _) >> [id: '1000']
            client.addLabel(_, _) >> [:]

            def task = Asciidoc2ConfluenceTask.From(config, RESOURCES)
            task.confluenceClient = client

        when:
            task.execute()

        then: 'one listing for the space the first two inputs publish into'
            1 * client.fetchPagesBySpaceKey('SPACE', _) >> [:]

        and: 'and one for the space the third names for itself'
            1 * client.fetchPagesBySpaceKey('OTHER', _) >> [:]
    }
}
