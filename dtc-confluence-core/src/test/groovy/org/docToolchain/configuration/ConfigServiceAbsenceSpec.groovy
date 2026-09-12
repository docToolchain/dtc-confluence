package org.docToolchain.configuration

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Covers the rule that decides when a configured value counts as absent.
 *
 * <p>It exists because ConfigObject answers a missing key with an empty ConfigObject rather than
 * null, so "absent" cannot simply mean null. The Groovy implementation inherited the rule from
 * Groovy truth; stating it explicitly means it has to be tested explicitly.</p>
 */
class ConfigServiceAbsenceSpec extends Specification {

    private ConfigService withValue(Object value) {
        ConfigObject config = new ConfigObject()
        config.put('probe', value)
        return new ConfigService(config)
    }

    @Unroll
    def 'a value of #description counts as absent'() {
        expect:
            withValue(value).getConfigProperty('probe') == null

        where:
            description        | value
            'an empty map'     | [:]
            'an empty list'    | []
            'an empty string'  | ''
            'false'            | false
            'integer zero'     | 0
            'a zero double'    | 0.0d
    }

    @Unroll
    def 'a value of #description is returned'() {
        expect:
            withValue(value).getConfigProperty('probe') == value

        where:
            description        | value
            'a filled map'     | [a: 1]
            'a filled list'    | ['a']
            'a string'         | 'text'
            'true'             | true
            'a non-zero int'   | 7
            'a non-zero double'| 1.5d
    }

    def 'an object of some other type is returned as it is'() {
        given:
            def value = new Date(0)

        expect:
            withValue(value).getConfigProperty('probe').is(value)
    }

    def 'a sub-tree prefix that matches nothing yields an empty map'() {
        expect:
            withValue('text').getFlatConfigSubTree('nothing.like.this') == [:]
    }

    def 'the sub-tree prefix matches on the path, so a longer key is included too'() {
        given: 'two keys where one is a prefix of the other'
            ConfigObject config = new ConfigObject()
            config.confluence = [api: 'a']
            config.confluenceExtra = [api: 'b']
            def service = new ConfigService(config)

        expect: """Pins current behaviour. startsWith matches the path, not a path segment, so a
                   sibling key sharing the prefix is pulled in. No configuration in use has such a
                   pair, and changing it is a decision about the typed configuration, not a fix."""
            service.getFlatConfigSubTree('confluence').size() == 2
    }
}
