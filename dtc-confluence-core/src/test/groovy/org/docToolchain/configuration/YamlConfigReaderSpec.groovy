package org.docToolchain.configuration

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * States how a YAML configuration becomes the object the rest of the code reads.
 */
class YamlConfigReaderSpec extends Specification {

    @TempDir
    Path dir

    private static ConfigObject read(String yaml) {
        return new YamlConfigReader().read(new StringReader(yaml))
    }

    def 'a nested mapping is reachable by dotted path'() {
        given:
            def config = read('''
                confluence:
                  api: https://cwiki.apache.org/confluence
                  spaceKey: SPACE
            ''')

        expect: 'which is what ConfigService asks for, and only ConfigObjects flatten'
            new ConfigService(config).getConfigProperty('confluence.api') ==
                'https://cwiki.apache.org/confluence'
            new ConfigService(config).getConfigProperty('confluence.spaceKey') == 'SPACE'
    }

    def 'a list of mappings survives as a list of mappings'() {
        given:
            def config = read('''
                confluence:
                  input:
                    - file: build/html5/one.html
                      ancestorId: '111'
                    - file: build/html5/two.html
            ''')

        expect:
            config.confluence.input.size() == 2
            config.confluence.input[0].file == 'build/html5/one.html'
            config.confluence.input[0].ancestorId == '111'
            config.confluence.input[1].file == 'build/html5/two.html'
    }

    def 'types are the ones YAML gives, not strings'() {
        given:
            def config = read('''
                confluence:
                  useV1Api: true
                  subpagesForSections: 2
                  pagePrefix: ''
            ''')

        expect: 'a boolean stays a boolean, which is what the version decision looks for'
            config.confluence.useV1Api instanceof Boolean
            config.confluence.useV1Api
            config.confluence.subpagesForSections == 2
            config.confluence.pagePrefix == ''
    }

    def 'deep nesting keeps working'() {
        given:
            def config = read('''
                confluence:
                  export:
                    api:
                      pageLimit: 100
            ''')

        expect:
            new ConfigService(config).getConfigProperty('confluence.export.api.pageLimit') == 100
    }

    def 'an empty file is a configuration that says nothing'() {
        expect:
            read('').isEmpty()
            read('# only a comment\n').isEmpty()
    }

    def 'a file that is not a mapping is refused rather than half-read'() {
        when:
            read('- just\n- a list\n')

        then:
            def e = thrown(IllegalArgumentException)
            e.message.contains('mapping at the top level')
    }

    def 'a duplicate key is refused rather than silently resolved'() {
        when:
            read('confluence:\n  api: one\n  api: two\n')

        then:
            thrown(Exception)
    }

    def 'nothing in the file can name a class to build'() {
        when: 'the classic YAML deserialisation attack'
            read('confluence: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://x/"]]\n')

        then:
            thrown(Exception)
    }

    def 'a file is read as UTF-8'() {
        given:
            def file = dir.resolve('config.yaml')
            file.toFile().setText('confluence:\n  pagePrefix: "Größe – "\n', 'UTF-8')

        when:
            def config = new YamlConfigReader().read(file)

        then:
            config.confluence.pagePrefix == 'Größe – '
    }
}
