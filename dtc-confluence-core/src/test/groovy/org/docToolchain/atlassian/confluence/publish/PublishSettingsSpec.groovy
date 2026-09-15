package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States what one input entry decides, and how "not given" is recognised.
 *
 * Absence arrives in two shapes: an empty ConfigObject where the section was built by property
 * assignment, and null where it was written as "confluence = [:]" with a with block. Reading one
 * and not the other has caused three separate defects in this project, so both are stated here.
 */
class PublishSettingsSpec extends Specification {

    /** The shape "confluence = [:]" with a with block leaves: a plain map, missing keys are null. */
    private static Map asPlainMap(Map values) {
        return new LinkedHashMap(values)
    }

    /** The shape property assignment leaves: missing keys answer with an empty ConfigObject. */
    private static ConfigObject asConfigObject(Map values) {
        def config = new ConfigObject()
        values.each { key, value -> config.put(key, value) }
        return config
    }

    def 'a per-input value wins over the global one'() {
        given:
            def settings = PublishSettings.of(
                [file: 'a.html', spaceKey: 'PERINPUT'], [spaceKey: 'GLOBAL'])

        expect:
            settings.spaceKey() == 'PERINPUT'
    }

    def 'the global value applies where the input says nothing'() {
        expect:
            PublishSettings.of(shape([file: 'a.html']), [spaceKey: 'GLOBAL']).spaceKey() == 'GLOBAL'

        where:
            shape << [{ asPlainMap(it) }, { asConfigObject(it) }]
    }

    def 'subpagesForSections defaults to a page per section'() {
        expect:
            PublishSettings.of(shape([:]), shape([:])).subpagesForSections() == 1

        where:
            shape << [{ asPlainMap(it) }, { asConfigObject(it) }]
    }

    def 'a configured zero is kept, and is not read as absent'() {
        expect: 'zero means one page, which is the setting this project publishes with most often'
            PublishSettings.of([:], [subpagesForSections: 0]).subpagesForSections() == 0
    }

    def 'a number written as text is read as a number'() {
        expect: 'YAML gives a number, a Groovy file may give either'
            PublishSettings.of([:], [subpagesForSections: '2']).subpagesForSections() == 2
    }

    def 'a value that is not a number falls back rather than failing the publish'() {
        expect:
            PublishSettings.of([:], [subpagesForSections: 'deep']).subpagesForSections() == 1
    }

    def 'a prefix nobody wrote is no prefix, in either shape'() {
        expect: "not the literal '[:]', which is what 'as String' makes of an empty ConfigObject"
            PublishSettings.of(shape([:]), shape([:])).pagePrefix() == ''
            PublishSettings.of(shape([:]), shape([:])).pageSuffix() == ''

        where:
            shape << [{ asPlainMap(it) }, { asConfigObject(it) }]
    }

    def 'a footnote label nobody wrote is the default'() {
        expect:
            PublishSettings.of(shape([:]), shape([:])).footnoteLabel() == 'Footnote'

        where:
            shape << [{ asPlainMap(it) }, { asConfigObject(it) }]
    }

    def 'a footnote label deliberately emptied stays empty'() {
        expect: 'an empty string is a value; only an absent entry gets the default'
            PublishSettings.of([:], [footnoteLabel: '']).footnoteLabel() == ''
    }

    def 'a removed option is refused with its migration'() {
        when:
            PublishSettings.of(input, confluence)

        then:
            def e = thrown(IllegalStateException)
            e.message == 'config problem'

        where:
            input                      | confluence
            [allInOnePage: true]       | [:]
            [createSubpages: false]    | [:]
            [preambleTitle: 'Intro']   | [:]
            [:]                        | [allInOnePage: false]
            [:]                        | [preambleTitle: 'Intro']
    }

    def 'the absence of a removed option is not an error'() {
        when: 'which is what a guard testing only for ConfigObject got backwards'
            def settings = PublishSettings.of(asPlainMap([file: 'a.html']), asPlainMap([:]))

        then:
            noExceptionThrown()
            settings.subpagesForSections() == 1
    }

    def 'an ancestor may be numbered or named'() {
        expect:
            PublishSettings.of([ancestorId: '4711'], [:]).ancestorId() == '4711'
            PublishSettings.of([ancestorName: 'Some Page'], [:]).ancestorName() == 'Some Page'

        and: 'and where there is neither, the page belongs at the root of the space'
            PublishSettings.of([:], [:]).ancestorId() == ''
    }

    def 'a global ancestor applies where the input names none'() {
        expect:
            PublishSettings.of([file: 'a.html'], [ancestorId: '99']).ancestorId() == '99'
    }
}
