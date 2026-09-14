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

    def 'a configuration that refers to itself is refused, not walked'() {
        when: 'an anchor pointing at the node that contains it - legal YAML, endless to walk'
            read('a: &x\n  b: *x\n')

        then:
            def e = thrown(IllegalArgumentException)
            e.message.contains('refers to itself')
    }

    def 'a list that refers to itself is refused too'() {
        when:
            read('a: &x\n  - *x\n')

        then:
            thrown(IllegalArgumentException)
    }

    def 'an alias that is not a cycle is still perfectly good'() {
        given:
            def config = read("""
                defaults: &defaults
                  spaceKey: SPACE
                confluence:
                  <<: *defaults
                  api: https://cwiki.apache.org/confluence
            """)

        expect: 'sharing a fragment is why anchors exist'
            config.confluence.spaceKey == 'SPACE'
            config.confluence.api == 'https://cwiki.apache.org/confluence'
    }

    def 'the input list can be added to'() {
        given: """Publishing appends the files it discovers to confluence.input when
                  inputHtmlFolder is set, and ConfigSlurper hands out a list that allows it."""
            def config = read('confluence:\n  input:\n    - file: a.html\n')

        when:
            config.confluence.input << [file: 'b.html']

        then:
            noExceptionThrown()
            config.confluence.input.size() == 2
    }

    def 'a nested list can be added to as well'() {
        given:
            def config = read('confluence:\n  imageDirs:\n    - images/.\n')

        when:
            config.confluence.imageDirs << 'more/.'

        then:
            config.confluence.imageDirs.size() == 2
    }

    def 'two keys that read the same are refused rather than one winning'() {
        when: 'YAML tells an integer key from a string key; a configuration path cannot'
            read('1: one\n"1": also one\n')

        then:
            def e = thrown(IllegalArgumentException)
            e.message.contains("read as '1'")
    }

    def 'keys that are not strings still work as long as they do not collide'() {
        given:
            def config = read('confluence:\n  1: first\n  two: second\n')

        expect:
            config.confluence['1'] == 'first'
            config.confluence.two == 'second'
    }

    def 'an entry of a list is a plain map, as ConfigSlurper leaves it'() {
        given: """The publisher asks an input entry for keys it may not have. A ConfigObject
                  answers those with an empty ConfigObject rather than null, which reads as
                  "configured" and overrides the global setting with nothing."""
            def config = read("""
                confluence:
                  subpagesForSections: 0
                  input:
                    - file: build/html5/one.html
            """)
            def entry = config.confluence.input[0]

        expect:
            !(entry instanceof ConfigObject)
            entry instanceof Map

        and: 'a key it does not have is absent, not an empty ConfigObject'
            entry.subpagesForSections == null
            (entry.subpagesForSections != null ? entry.subpagesForSections
                : config.confluence.subpagesForSections) == 0
    }

    def 'a mapping that is not inside a list is still a ConfigObject'() {
        given:
            def config = read('confluence:\n  export:\n    api:\n      pageLimit: 100\n')

        expect: 'because a dotted lookup goes through ConfigObject.flatten'
            config.confluence instanceof ConfigObject
            config.confluence.export instanceof ConfigObject
            new ConfigService(config).getConfigProperty('confluence.export.api.pageLimit') == 100
    }

    def 'an alias chain does not explode into billions of nodes'() {
        given: """Nine levels of nine references each: 81 aliases, under any sane alias limit, and
                  three billion leaves if every alias is rebuilt. Measured before the fix:
                  OutOfMemoryError in about a second."""
            def yaml = new StringBuilder('a0: &a0 ["x","x","x","x","x","x","x","x","x"]\n')
            (1..9).each { level ->
                yaml << "a${level}: &a${level} [" + (['*a' + (level - 1)] * 9).join(',') + "]\n"
            }

        when:
            def config = read(yaml.toString())

        then: 'the parser shares the node an alias refers to, and so does the conversion'
            noExceptionThrown()
            config.a9.size() == 9
    }

    def 'an alias and its anchor are the same object afterwards'() {
        given:
            def config = read("""
                defaults: &defaults
                  - one
                  - two
                first: *defaults
                second: *defaults
            """)

        expect: 'which is what keeps a chain of them from multiplying'
            config.first.is(config.second)
            config.first == ['one', 'two']
    }

    def 'an explicit null is not an empty configuration'() {
        when: 'load() answers null for this and for an empty file alike'
            read(document)

        then: 'a document that says "nothing" is still a document, and not a mapping'
            def e = thrown(IllegalArgumentException)
            e.message.contains('mapping at the top level')

        where:
            document << ['null\n', '~\n', '--- null\n']
    }

    def 'a file that truly says nothing is still accepted'() {
        expect:
            read('').isEmpty()
            read('# only a comment\n').isEmpty()
            read('\n\n').isEmpty()
    }

    def 'two aliases of one input entry are two entries'() {
        given: """The publisher writes back into an input entry - it canonicalises input.file in
                  place. Sharing one object would let the second entry see the first one's path
                  and publish the same page twice."""
            def config = read("""
                confluence:
                  input:
                    - &doc
                      file: a.html
                    - *doc
            """)

        when: 'the publisher rewrites the path of the first entry'
            config.confluence.input[0].file = 'canonical/a.html'

        then: 'the second is untouched, as two separate files must be'
            !config.confluence.input[0].is(config.confluence.input[1])
            config.confluence.input[1].file == 'a.html'
    }

    def 'a mapping aliased into a list is a plain map there'() {
        given: """Anchored outside a list it becomes a ConfigObject; used as an input entry it has
                  to be a plain map, or a key it does not have answers with an empty ConfigObject
                  instead of null - and the publisher reads that as "set per input"."""
            def config = read("""
                defaults: &d
                  file: x.html
                confluence:
                  subpagesForSections: 0
                  input:
                    - *d
            """)
            def entry = config.confluence.input[0]

        expect:
            config.defaults instanceof ConfigObject
            !(entry instanceof ConfigObject)

        and: 'so the global setting is what applies'
            entry.subpagesForSections == null
            (entry.subpagesForSections != null ? entry.subpagesForSections
                : config.confluence.subpagesForSections) == 0
    }

    def 'a mapping used both inside and outside a list gets both shapes'() {
        given:
            def config = read("""
                shared: &s
                  file: a.html
                confluence:
                  input:
                    - *s
            """)

        expect: 'the first use must not decide the second'
            config.shared instanceof ConfigObject
            !(config.confluence.input[0] instanceof ConfigObject)
            config.confluence.input[0].file == 'a.html'
    }

    def 'an alias chain built from mappings does not explode either'() {
        given: 'the same shape as the list bomb, with a mapping at every level'
            def yaml = new StringBuilder('a0: &a0 {k: v}\n')
            (1..9).each { level ->
                def refs = (1..9).collect { "k${it}: *a${level - 1}" }.join(', ')
                yaml << "a${level}: &a${level} {${refs}}\n"
            }

        when:
            def config = read(yaml.toString())

        then: 'the mappings are outside any list, so they are shared'
            noExceptionThrown()
            config.a9.size() == 9
    }

    def 'a mapping chain aliased into a list does not explode'() {
        given: """The list entry itself is copied rather than shared. Passing that down would make
                  every mapping below it non-shareable too - nine levels of nine references is 82
                  aliases, under the limit, and measured before the fix an OutOfMemoryError."""
            def yaml = new StringBuilder('m0: &m0 {k: v}\n')
            (1..9).each { level ->
                def refs = (0..8).collect { "k${it}: *m${level - 1}" }.join(', ')
                yaml << "m${level}: &m${level} {${refs}}\n"
            }
            yaml << 'confluence:\n  input:\n    - *m9\n'

        when:
            def config = read(yaml.toString())

        then:
            noExceptionThrown()
            config.confluence.input[0].size() == 9

        and: 'the entry is still its own object, and what it contains is still shared'
            !(config.confluence.input[0] instanceof ConfigObject)
            config.confluence.input[0].k0.is(config.m9.k0)
    }
}
