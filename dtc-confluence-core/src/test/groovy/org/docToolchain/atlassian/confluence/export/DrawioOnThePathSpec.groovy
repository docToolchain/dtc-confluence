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
                  (0-based before v27.0.2)" and draws nothing.'''

        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, aspect) == page

        where:
            aspect                 || page
            'view-context'         || 1
            'view-building-blocks' || 2
            'view-core'            || 3
    }

    def 'no view asked for is the first page, which is what the default preview shows'() {
        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, '') == 1
            DrawioOnThePath.pageOf(THREE_VIEWS, null) == 1
    }

    def 'a view the file does not hold is drawn as the first, because a picture beats an error'() {
        expect:
            DrawioOnThePath.pageOf(THREE_VIEWS, 'view-that-was-deleted') == 1
            DrawioOnThePath.pageOf('<mxfile/>', 'view-core') == 1
    }
}
