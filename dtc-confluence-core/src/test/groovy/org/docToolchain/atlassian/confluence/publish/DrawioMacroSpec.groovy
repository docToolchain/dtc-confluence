package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States what a draw.io macro this publisher writes carries, and what it deliberately does not.
 *
 * Measured against Confluence Data Center 9.2.21 by publishing the shortest macro that could work
 * and reading back what the server resolved it to.
 */
class DrawioMacroSpec extends Specification {

    def 'it names the diagram and how big it is, and nothing else'() {
        given: '''Measured: a macro carrying only diagramName came back resolved with links auto,
                  tbstyle top, border true, lbox true and maxScale 1 - the very values the app
                  writes out itself. Parameters that say nothing a reader would notice are
                  parameters a publisher would be inventing.'''

        when:
            def macro = DrawioMacro.showing('architecture-context', '700', '515')

        then:
            macro == '<ac:structured-macro ac:name="drawio" ac:schema-version="1">' +
                '<ac:parameter ac:name="diagramName">architecture-context</ac:parameter>' +
                '<ac:parameter ac:name="diagramWidth">700</ac:parameter>' +
                '<ac:parameter ac:name="height">515</ac:parameter>' +
                '</ac:structured-macro>'
    }

    def 'the size is there because it is what reaches the page'() {
        given: '''Measured: the rendered container is width:Wpx;height:Hpx. Without diagramWidth
                  it carries the literal width:${diagramWidth}px and the export view a PDF is made
                  of carries style="px;max-width: 100%;"; without height, two unsubstituted
                  ${diagramHeight} were counted on a published page, against none on either page
                  the app wrote itself.'''

        expect:
            DrawioMacro.showing('x', '500', '370').contains(
                '<ac:parameter ac:name="diagramWidth">500</ac:parameter>')
            DrawioMacro.showing('x', '500', '370').contains(
                '<ac:parameter ac:name="height">370</ac:parameter>')
    }

    def 'a height that could not be worked out is left out rather than invented'() {
        given: '''The diagram's own size is nowhere in the file the document names, so the only
                  honest height is the preview's shape at that width. Where the preview cannot be
                  measured there is no honest number at all.'''

        expect:
            !DrawioMacro.showing('x', '500', '').contains('height')
    }

    def 'no macro id, because a publisher cannot invent a stable one'() {
        given: '''A fresh id on every run would change the body, and the page would be rewritten
                  on every publish for no change at all. Confluence assigns one when the page is
                  saved: measured, the macro came back with a macroId of the server's own.'''

        expect:
            !DrawioMacro.showing('x', '500', '370').contains('macro-id')
            DrawioMacro.showing('x', '500', '370') == DrawioMacro.showing('x', '500', '370')
    }

    def 'no parameter is ever empty, because an empty one eats the ones behind it'() {
        given: '''jsoup's HTML parser does not close a self-closing ac:parameter, so the
                  parameters after it end up inside it - and Confluence answers a parameter within
                  a parameter with "500 Unhandled element type parameter".'''

        expect:
            !DrawioMacro.showing('x', '500', '370').contains('/>')
            !DrawioMacro.showing('x', '500', '370').contains('></ac:parameter>')
    }

    def 'a quote in the name does not cut the diagram short'() {
        given: 'the name comes from a file name, and the fragment is parsed again'

        when:
            def macro = DrawioMacro.showing('a"b', '500', '370')

        then:
            macro.contains('>a&quot;b</ac:parameter>')
    }
}
