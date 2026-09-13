package org.docToolchain.confluence.cli

import picocli.CommandLine
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States how the command line arrives at a configuration: which file it reads, where the API URL
 * comes from, and how the API version is chosen when nobody said.
 */
class ConfigurationOptionsSpec extends Specification {

    @TempDir
    Path docDir

    private ConfigurationOptions optionsFor(String api, String... arguments) {
        docDir.resolve('config.groovy').toFile().text = """
            confluence = [:]
            confluence.with {
                api = '${api}'
                spaceKey = 'SPACE'
                input = []
            }
        """
        def options = new ConfigurationOptions()
        new CommandLine(options).parseArgs(
            ['-d', docDir.toString(), '-c', 'config.groovy', *arguments] as String[])
        return options
    }

    def 'a Data Center URL is served by API v1'() {
        when:
            def config = optionsFor('https://cwiki.apache.org/confluence').load()

        then: 'v2 exists in Cloud only, and answers 404 on Data Center'
            config.confluence.useV1Api == true
    }

    def 'a Cloud URL is served by API v2'() {
        when:
            def config = optionsFor('https://example.atlassian.net/wiki').load()

        then:
            config.confluence.useV1Api == false
    }

    def 'a configured version is left alone'() {
        given: 'a Cloud URL that the configuration overrides'
            docDir.resolve('config.groovy').toFile().text = """
                confluence = [:]
                confluence.with {
                    api = 'https://example.atlassian.net/wiki'
                    useV1Api = true
                    input = []
                }
            """
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'config.groovy')

        when:
            def config = options.load()

        then: 'the URL does not get a say'
            config.confluence.useV1Api == true
    }

    def 'the api option overrides the configured URL'() {
        when:
            def config = optionsFor('https://configured.example.org',
                '--api', 'https://given.atlassian.net/wiki').load()

        then:
            config.confluence.api == 'https://given.atlassian.net/wiki'
    }

    def 'the doc directory is reported as given'() {
        expect:
            optionsFor('https://cwiki.apache.org/confluence').docDir() == docDir.toString()
    }

    def 'a missing configuration file is refused rather than half-read'() {
        given:
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'absent.groovy')

        when:
            options.load()

        then:
            thrown(FileNotFoundException)
    }
}
