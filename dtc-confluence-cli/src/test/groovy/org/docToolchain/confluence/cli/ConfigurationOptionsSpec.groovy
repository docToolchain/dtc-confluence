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

    def 'the api option overrides the configured URL'() {
        when:
            def config = optionsFor('https://configured.example.org',
                '--api', 'https://given.atlassian.net/wiki').load()

        then: 'ConfluenceApiVersion then derives the API version from this one'
            config.confluence.api == 'https://given.atlassian.net/wiki'
    }

    def 'the document directory is the one given, not the configuration file\'s parent'() {
        given: 'a configuration one level down'
            docDir.resolve('sub').toFile().mkdirs()
            docDir.resolve('sub/config.groovy').toFile().text = """
                confluence = [:]
                confluence.with { api = 'https://cwiki.apache.org/confluence'; input = [] }
            """
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'sub/config.groovy')

        when:
            def config = options.load()

        then: 'paths in the configuration resolve against one root, the one --doc-dir named'
            config.docDir == docDir.toString()
            options.docDir() == docDir.toString()
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
