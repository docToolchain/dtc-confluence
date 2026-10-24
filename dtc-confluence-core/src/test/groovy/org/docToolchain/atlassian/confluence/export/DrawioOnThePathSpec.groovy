package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States how a view is turned into the page of the file that draws it.
 *
 * Only this much of the renderer can be stated: the rest starts an application that may not be on
 * the machine running the build, and an export is not allowed to need one either.
 */
class DrawioOnThePathSpec extends Specification {

    private static final String THREE_VIEWS =
        '<mxfile><diagram id="view-context" name="Context"/>' +
        '<diagram id="view-building-blocks" name="Blocks"/>' +
        '<diagram id="view-core" name="Core"/></mxfile>'

    def 'a view is the page it sits at, counting from one'() {
        given: '''Measured: draw.io answers --page-index 0 with "pages are numbered from 1
                  (0-based before v27.0.2)" and draws nothing. 32.6.1 is the one on this
                  machine, which answers --version with that and nothing else.'''

        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, aspect, '32.6.1') == OptionalInt.of(page)

        where:
            aspect                 || page
            'view-context'         || 1
            'view-building-blocks' || 2
            'view-core'            || 3
    }

    def 'no view asked for is the first page, which is what the default preview shows'() {
        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, '', '32.6.1') == OptionalInt.of(1)
            DrawioOnThePath.pageOf(THREE_VIEWS, null, '32.6.1') == OptionalInt.of(1)
    }

    def 'a view the file does not hold is drawn as the first, because a picture beats an error'() {
        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, 'view-that-was-deleted', '32.6.1') ==
                OptionalInt.of(1)
            DrawioOnThePath.pageOf('<mxfile/>', 'view-core', '32.6.1') == OptionalInt.of(1)
    }

    def 'a draw.io that may count pages from zero is not asked to draw at all'() {
        given: '''Before 27.0.2 --page-index counted from zero, so the one-based index this
                  supplies drew the view after the one asked for and a refresh would overwrite a
                  preview with the following view. A stale picture of the right view beats a fresh
                  picture of the wrong one, and draw.io is never required - so an installation
                  that cannot be shown to count from one is left unused.'''

        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, aspect, said).isEmpty() == refused

        where:
            said                                  | aspect      || refused
            '32.6.1'                              | 'view-core' || false
            '27.0.2'                              | 'view-core' || false
            '27.0.3'                              | ''          || false
            '27.1.0'                              | ''          || false
            '28.0.0'                              | 'view-core' || false
            '27.0.1'                              | 'view-core' || true
            '27.0.0'                              | ''          || true
            '26.1.3'                              | 'view-core' || true
            '9.0.0'                               | ''          || true
            '27.0'                                | ''          || true
            ''                                    | ''          || true
            'zsh: command not found: drawio'      | 'view-core' || true
    }
}
