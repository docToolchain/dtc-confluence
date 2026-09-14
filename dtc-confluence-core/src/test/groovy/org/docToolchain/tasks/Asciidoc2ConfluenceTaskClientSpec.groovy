package org.docToolchain.tasks

import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV1
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV2
import spock.lang.Specification

/**
 * States which client the publisher builds for itself.
 *
 * This task descends from DocToolchainTask rather than from AbstractConfluenceTask, so it is a
 * second place where the API version is chosen. ConfluenceApiVersionSpec covers the decision;
 * this covers that the publisher actually asks it.
 */
class Asciidoc2ConfluenceTaskClientSpec extends Specification {

    private static Asciidoc2ConfluenceTask taskFor(Map confluence) {
        def config = new ConfigObject()
        config.confluence = [spaceKey: 'SPACE', credentials: 'x', input: []] + confluence
        return new Asciidoc2ConfluenceTask(config, '.')
    }

    def 'a Data Center URL without a setting gets the v1 client'() {
        when: 'the same configuration that used to reach a v2 endpoint and a 404'
            def task = taskFor([api: 'https://cwiki.apache.org/confluence'])

        then:
            task.confluenceClient instanceof ConfluenceClientV1
    }

    def 'a Cloud URL without a setting gets the v2 client'() {
        when:
            def task = taskFor([api: 'https://example.atlassian.net/wiki'])

        then:
            task.confluenceClient instanceof ConfluenceClientV2
    }

    def 'an explicit setting is obeyed against the URL'() {
        when:
            def task = taskFor([api: api, useV1Api: configured])

        then:
            task.confluenceClient.class == expected

        where:
            api                                   | configured || expected
            'https://example.atlassian.net/wiki'  | true       || ConfluenceClientV1
            'https://cwiki.apache.org/confluence' | false      || ConfluenceClientV2
    }
}
