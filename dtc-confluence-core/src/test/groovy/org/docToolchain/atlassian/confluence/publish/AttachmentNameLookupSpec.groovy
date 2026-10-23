package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States how a reference the export sanitised finds the attachment it came from.
 *
 * The export writes every attachment through PageNaming.sanitizeAttachmentName, which replaces
 * whitespace, a colon and a path separator with "_", and the document then refers to the file
 * under that name. Published literally, "my_image.png" is a second attachment beside the
 * "my image.png" Confluence holds, and the page's pictures and macros split across the two.
 *
 * The answer cannot be computed from the name: "my_image.png" is a legal attachment name of its
 * own, so the mapping is not invertible. It is asked of the page instead.
 */
class AttachmentNameLookupSpec extends Specification {

    /** What the page was asked for, so that asking at all can be a statement. */
    private int timesAsked = 0

    private AttachmentNameLookup holding(String... names) {
        return new AttachmentNameLookup({
            timesAsked++
            names as List
        })
    }

    def 'a reference the export sanitised is published under the name Confluence holds'() {
        expect:
            holding('my image.png').resolve('my_image.png') == 'my image.png'
    }

    def 'every character the export replaces is matched back'() {
        expect: 'the rule is the export sanitisation, read forwards over what the page holds'
            holding(held).resolve(reference) == held

        where:
            held             || reference
            'my image.png'   || 'my_image.png'
            'a\tb.png'       || 'a_b.png'
            'two  words.png' || 'two__words.png'
            'C:plan.png'     || 'C_plan.png'
            'notes/old.png'  || 'notes_old.png'
            'dir\\old.png'   || 'dir_old.png'
    }

    def 'a name the page does not hold is published as the document refers to it'() {
        expect:
            holding('other.png', 'diagram.png').resolve('my_image.png') == 'my_image.png'
    }

    def 'a name Confluence holds as it is stays as it is'() {
        given: 'the common case, and the one that must cost nothing'
        expect:
            holding('diagram.png').resolve('diagram.png') == 'diagram.png'
    }

    def 'an attachment of another name is not matched by its stem'() {
        given: 'the rule is equality after sanitising, not similarity'
        expect:
            holding('my image.jpg').resolve('my_image.png') == 'my_image.png'
    }

    def 'two attachments of one sanitised name are named rather than guessed between'() {
        given: '''A page can hold "my image.png" and "my_image.png" at once; both are written to
                  disk as "my_image.png", so which of them this file came from cannot be read off
                  its name. Picking one would write over somebody else's picture.'''
            def lookup = holding('my image.png', 'my_image.png')
            def resolved = null

        when:
            def said = outputOf { resolved = lookup.resolve('my_image.png') }

        then: 'the name the document refers to, which is what every run did before'
            resolved == 'my_image.png'

        and: 'and both candidates are named, because the author is the only one who can tell'
            said.contains("'my_image.png'")
            said.contains("'my image.png'")
            said.contains('WARN')
    }

    def 'the page is asked once, however many names are resolved'() {
        given: 'one request per page, not one per picture'
            def lookup = holding('my image.png', 'other.png')

        when:
            lookup.resolve('my_image.png')
            lookup.resolve('other.png')
            lookup.resolve('third.png')

        then:
            timesAsked == 1
    }

    def 'a page is not asked at all where nothing has to be matched'() {
        given: 'a document without attachments pays nothing for this'
            holding('my image.png')

        expect:
            timesAsked == 0
    }

    def 'a page that holds nothing matches nothing'() {
        expect:
            holding().resolve('my_image.png') == 'my_image.png'
    }

    def 'where there is no page there is nothing to match against'() {
        given: 'a page about to be created holds nothing, and must not be asked'
        expect:
            AttachmentNameLookup.NONE.resolve('my_image.png') == 'my_image.png'
    }

    private String outputOf(Closure work) {
        def captured = new ByteArrayOutputStream()
        def previous = System.out
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            work()
        } finally {
            System.out = previous
        }
        return captured.toString('UTF-8')
    }
}
