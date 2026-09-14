package org.docToolchain.confluence.cli

import picocli.CommandLine
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States what init writes, and that what it writes can then be found and read.
 */
class InitCommandSpec extends Specification {

    @TempDir
    Path docDir

    private File written() { docDir.resolve('.dtc-confluence.yaml').toFile() }

    private int run(String... extra) {
        return new CommandLine(new InitCommand())
            .execute(['-d', docDir.toString(), *extra] as String[])
    }

    def 'it writes a hidden YAML configuration'() {
        when:
            def exit = run()

        then: 'hidden, because it configures the tool rather than describing the documentation'
            exit == 0
            written().exists()
            written().name.startsWith('.')
            written().name.endsWith('.yaml')
    }

    def 'what it writes is what the reader has to decide'() {
        when:
            run('--api', 'https://cwiki.apache.org/confluence', '--space', 'SPACE',
                '--ancestor-id', '4711', '--file', 'build/html5/manual.html')

        then:
            def text = written().text
            text.contains('api: https://cwiki.apache.org/confluence')
            text.contains('spaceKey: SPACE')
            text.contains("ancestorId: '4711'")
            text.contains('file: build/html5/manual.html')
    }

    def 'an ancestor nobody named is left as a comment, not as a wrong value'() {
        when:
            run()

        then:
            written().text.contains("# ancestorId:")
            !(written().text =~ /(?m)^\s+ancestorId:/)
    }

    def 'no credential is ever written into it'() {
        when:
            run()

        then: 'a configuration file is committed; a token must not be'
            !written().text.contains('bearerToken:')
            !written().text.contains('credentials:')
            written().text.contains('CONFLUENCE_BEARER_TOKEN')
    }

    def 'it refuses to overwrite what is already there'() {
        given:
            written().text = 'confluence:\n  api: https://kept.example\n'

        when:
            def exit = run()

        then:
            exit != 0
            written().text.contains('kept.example')
    }

    def 'force replaces it'() {
        given:
            written().text = 'confluence:\n  api: https://replaced.example\n'

        when:
            def exit = run('--force', '--api', 'https://new.example')

        then:
            exit == 0
            written().text.contains('new.example')
            !written().text.contains('replaced.example')
    }

    def 'the result is a configuration the tool can read back'() {
        given:
            run('--api', 'https://cwiki.apache.org/confluence', '--space', 'SPACE',
                '--ancestor-id', '4711')

        when: 'found without being named, which is the point of the hidden default'
            def options = new ConfigurationOptions()
            new CommandLine(options).parseArgs('-d', docDir.toString())
            def config = options.load()

        then:
            config.confluence.api == 'https://cwiki.apache.org/confluence'
            config.confluence.spaceKey == 'SPACE'
            config.confluence.subpagesForSections == 1
            config.confluence.input[0].ancestorId == '4711'
    }
}
