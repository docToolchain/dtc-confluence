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

    def 'a section written as a map literal survives'() {
        given: """ConfigSlurper leaves this a plain LinkedHashMap rather than a ConfigObject, and
                  the core reads it just as well, so the CLI must not throw it away."""
            docDir.resolve('literal.groovy').toFile().text = """
                confluence = [api: 'https://cwiki.apache.org/confluence',
                              spaceKey: 'SPACE', pageSuffix: ' (copy)', input: []]
            """
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'literal.groovy')

        when:
            def config = options.load()

        then: 'everything the file said is still there'
            config.confluence.api == 'https://cwiki.apache.org/confluence'
            config.confluence.spaceKey == 'SPACE'
            config.confluence.pageSuffix == ' (copy)'
    }

    def 'an override reaches a section written as a map literal'() {
        given:
            docDir.resolve('literal.groovy').toFile().text = """
                confluence = [api: 'https://configured.example.org', spaceKey: 'SPACE', input: []]
            """
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'literal.groovy',
                '--api', 'https://given.example.org')

        when:
            def config = options.load()

        then: 'the override lands, and the rest of the section is untouched'
            config.confluence.api == 'https://given.example.org'
            config.confluence.spaceKey == 'SPACE'
    }

    def 'a configuration without a confluence section at all is accepted'() {
        given:
            docDir.resolve('bare.groovy').toFile().text = 'outputPath = "build"'
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString(), '-c', 'bare.groovy',
                '--api', 'https://given.example.org')

        when:
            def config = options.load()

        then: 'the section is created rather than the load failing'
            config.confluence.api == 'https://given.example.org'
    }
}
