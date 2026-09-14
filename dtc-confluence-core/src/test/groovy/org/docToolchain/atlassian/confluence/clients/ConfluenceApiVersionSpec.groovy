package org.docToolchain.atlassian.confluence.clients

import org.docToolchain.configuration.ConfigService
import spock.lang.Specification

/**
 * States which REST API a configuration ends up speaking.
 *
 * Getting this wrong is expensive to diagnose: v2 exists in Cloud only, and Data Center answers
 * its paths with a 404 that names nothing.
 */
class ConfluenceApiVersionSpec extends Specification {

    private static ConfigService serviceFor(Map confluence) {
        def config = new ConfigObject()
        config.confluence = confluence
        return new ConfigService(config)
    }

    def 'a configured setting is taken as it stands'() {
        expect: 'even where it contradicts the URL'
            ConfluenceApiVersion.useV1(serviceFor([api: api, useV1Api: configured])) == configured

        where:
            api                             | configured
            'https://example.atlassian.net' | true
            'https://cwiki.apache.org'      | false
    }

    def 'without a setting the deployment is recognised by host'() {
        expect:
            ConfluenceApiVersion.useV1(serviceFor([api: api])) == v1

        where:
            api                                      || v1
            'https://example.atlassian.net'          || false
            'https://example.atlassian.net/wiki'     || false
            'https://EXAMPLE.ATLASSIAN.NET/wiki'     || false
            'https://cwiki.apache.org/confluence'    || true
            'https://example.atlassian.net.invalid/' || true
            'https://self.hosted/x?q=.atlassian.net' || true
    }

    def 'an API URL that is not a URL at all falls back to v1'() {
        expect: 'v1 exists on Cloud as well, while v2 exists nowhere else'
            ConfluenceApiVersion.useV1(serviceFor([api: 'not a url at all']))
    }

    def 'a missing API URL falls back to v1'() {
        expect:
            ConfluenceApiVersion.useV1(serviceFor([:]))
    }
}
