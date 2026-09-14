package org.docToolchain.confluence.cli

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States which file is used when the command line does not name one.
 */
class ConfigurationFileSpec extends Specification {

    @TempDir
    Path dir

    private void write(String name) {
        dir.resolve(name).toFile().text = 'confluence:\n  api: https://example\n'
    }

    def 'the hidden YAML name comes first'() {
        given:
            write('.dtc-confluence.yaml')
            write('docToolchainConfig.groovy')

        expect:
            ConfigurationFile.find(dir.toString()) == '.dtc-confluence.yaml'
    }

    def 'a docToolchain configuration is still found'() {
        given: 'so a project that has one keeps working without being converted'
            write('docToolchainConfig.groovy')

        expect:
            ConfigurationFile.find(dir.toString()) == 'docToolchainConfig.groovy'
    }

    def 'each spelling is recognised'() {
        given:
            write(name)

        expect:
            ConfigurationFile.find(dir.toString()) == name

        where:
            name << ['.dtc-confluence.yaml', '.dtc-confluence.yml',
                     'dtc-confluence.yaml', 'dtc-confluence.yml']
    }

    def 'finding nothing says what was looked for and what to do'() {
        when:
            ConfigurationFile.find(dir.toString())

        then:
            def e = thrown(FileNotFoundException)
            e.message.contains('.dtc-confluence.yaml')
            e.message.contains('docToolchainConfig.groovy')
            e.message.contains('init')
    }

    def 'a directory is not a configuration file'() {
        given:
            dir.resolve('.dtc-confluence.yaml').toFile().mkdirs()

        when:
            ConfigurationFile.find(dir.toString())

        then:
            thrown(FileNotFoundException)
    }
}
