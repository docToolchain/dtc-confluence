package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States the one contract that makes several views of one diagram possible.
 *
 * A picture of a view carries nothing inside it, so its name is the only thing that can say which
 * view it is of. A hyphen is ordinary in file names, so the name is never read alone: it offers
 * readings, and the directory and the diagram decide between them.
 */
class ViewReferenceSpec extends Specification {

    private static final String SHARED = '<mxfile>' +
        '<diagram id="view-context" name="System Context">a</diagram>' +
        '<diagram id="view-core" name="Core Packages">b</diagram>' +
        '</mxfile>'

    def 'a name with one hyphen offers one reading'() {
        when:
            def candidates = ViewReference.candidatesIn('architecture-core.svg')

        then:
            candidates*.diagramName() == ['architecture']
            candidates*.aspect() == ['core']

        and: 'and names the file that would settle it'
            candidates[0].sourceFileName() == 'architecture.drawio'
    }

    def 'a name with several offers them all, longest diagram name first'() {
        given: '''architecture-view-core could be the core view of architecture-view or the
                  view-core view of architecture. The directory knows; the name does not.'''

        when:
            def candidates = ViewReference.candidatesIn('architecture-view-core.svg')

        then:
            candidates*.diagramName() == ['architecture-view', 'architecture']
            candidates*.aspect() == ['core', 'view-core']
    }

    def 'a name without a separator offers nothing'() {
        expect:
            ViewReference.candidatesIn(name).isEmpty()

        where:
            name << ['architecture.svg', 'architecture', '-core.svg', 'architecture-.svg']
    }

    def 'the extension is part of neither name'() {
        expect:
            ViewReference.candidatesIn("architecture-core.${extension}")[0].aspect() == 'core'

        where:
            extension << ['svg', 'png', 'jpeg']
    }

    def 'the page is the one the diagram spells, not the one the file name does'() {
        given: '''A generator may write its pages as view-core and export them as
                  architecture-core.svg, which is a reasonable thing to do and would otherwise
                  force this to know that generator's prefix. So the file is asked.'''

        expect:
            ViewReference.candidatesIn('architecture-core.svg')[0].pageOf(SHARED) ==
                Optional.of('view-core')
    }

    def 'both readings of a longer name can land on the same page'() {
        given: '''Which is not a problem, because the page is not what tells them apart: reading
                  it as the "core" view and as the "view-core" view both arrive at view-core, and
                  what decides between them is which diagram file is actually there.'''

        expect:
            ViewReference.candidatesIn('architecture-view-core.svg')
                .collect { it.pageOf(SHARED) }*.get() == ['view-core', 'view-core']
    }

    def 'a name no page answers to resolves to nothing'() {
        expect:
            ViewReference.candidatesIn('architecture-missing.svg')[0].pageOf(SHARED).empty
    }

    def 'a name two pages could answer to resolves to nothing'() {
        given: '''Guessing between them would pin a spot to the wrong view, which reads as a page
                  that is simply wrong rather than as one that is missing.'''
            def ambiguous = '<mxfile><diagram id="left-core">a</diagram>' +
                '<diagram id="right-core">b</diagram></mxfile>'

        expect:
            ViewReference.candidatesIn('architecture-core.svg')[0].pageOf(ambiguous).empty
    }
}
