package org.docToolchain.atlassian.confluence.export

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * What an exported page tree does when AsciiDoc reads it, rather than what it says.
 *
 * A specification that compares the include line against a string cannot tell a working attribute
 * from an ignored one: `levelOffset` and `leveloffset` are the same line to a reader and not to
 * the include directive, whose attribute list is matched literally against the names it knows.
 * (Document attributes elsewhere are case-folded; this is a rule about an include's brackets only.)
 * The misspelling was accepted without complaint for as long as it was only ever compared with
 * itself, and every page of an exported tree was included at the level it was written at.
 *
 * This renders instead, so every assertion is about the document the reader gets - and the pages
 * are composed from the export's own header and include lines rather than retyped, because where
 * a heading ends up is decided by three things at once: the level the title is written at, the
 * offset the include carries, and the level the body starts at.
 */
@Requires({ ChildIncludeLevelSpec.asciidoctorIsInstalled() })
class ChildIncludeLevelSpec extends Specification {

    @TempDir
    Path directory

    /** Three heading levels in a page body, which is what pandoc writes for h1, h2 and h3. */
    private static final String THREE_LEVELS =
            '== One\n\nalpha\n\n=== Two\n\nbeta\n\n==== Three\n\ngamma\n'

    /** A body with no heading of its own, so that only the page titles spend levels. */
    private static final String NO_HEADING = 'alpha\n'

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def setup() {
        // What the file header includes, written beside the pages as the export writes it. The
        // style block is why the header has to end with a blank line: read inside the header it
        // is an unterminated pass block, and the CSS lands in the page as text.
        Files.writeString(directory.resolve('_config.adoc'),
                '++++\n<style>\ndiv.ulist ul {\nmargin-left: 1em !important;\n}\n'
                        + '</style>\n++++\n\n')
        Files.writeString(directory.resolve('_menu.adoc'),
                '* xref:{jbake-root}Level_0.adoc[Level 0]\n')
    }

    def 'a child page is rendered one level below the page that includes it'() {
        given:
            def root = chain(3, THREE_LEVELS)

        when:
            def rendered = render(root, true)

        then: 'the page the render starts at carries its own title'
            rendered.contains('<h1>Level 0</h1>')

        and: 'the child below it, not beside it, and the grandchild below the child'
            rendered.contains('<h2 id="_level_1">Level 1</h2>')
            rendered.contains('<h3 id="_level_2">Level 2</h3>')
    }

    def 'a page read on its own carries its title with its body below it'() {
        given: 'the deepest page of the tree, which is the one furthest from the root'
            chain(3, THREE_LEVELS)

        when: 'read as a file of its own, without the tree around it'
            def rendered = render(directory.resolve('Level_0/Level_1/Level_2.adoc'))

        then: """its title, which an embedded render leaves out unless the page asks for it - and
                 embedded is how a microsite renders every page"""
            rendered.contains('<h1>Level 2</h1>')

        and: """the body inside that title rather than beside it: while the title was a section of
                its own, the body's first heading was written at the same level as the title and
                the page read as two"""
            rendered.contains('<h2 id="_one">One</h2>')
            rendered.contains('<h3 id="_two">Two</h3>')
            rendered.contains('<h4 id="_three">Three</h4>')
    }

    def 'three pages deep, the deepest body keeps all three of its heading levels'() {
        given: """the shape that used to overflow: a root, a child, a grandchild, and three
                  heading levels in the grandchild's body. Nested below a level-1 title the
                  deepest of them came out as <h7>, which is no HTML element - no heading, no
                  entry in the outline, nothing in a table of contents, and asciidoctor says
                  nothing about it."""
            def root = chain(3, THREE_LEVELS)

        when:
            def rendered = render(root, true)

        then: 'the grandchild sits at the third level, and its body fills the three below it'
            rendered.contains('<h3 id="_level_2">Level 2</h3>')
            rendered.contains('<h4 id="_one_3">One</h4>')
            rendered.contains('<h5 id="_two_3">Two</h5>')
            rendered.contains('<h6 id="_three_3">Three</h6>')

        and: 'and nothing ran past the sixth level'
            !rendered.contains('<h7')
    }

    def 'a tree of six pages still ends in a heading'() {
        given: """AsciiDoc has levels 0 to 5. The root spends the first and every step down the
                  tree one more, so this is the whole budget where the pages carry no headings of
                  their own."""
            def root = chain(6, NO_HEADING)

        when:
            def rendered = render(root, true)

        then: 'the sixth page is a heading, at the last level there is'
            rendered.contains('<h6 id="_level_5">Level 5</h6>')
            !rendered.contains('<h7')
    }

    def 'one page further down the heading stops being a heading, and nothing says so'() {
        given: """What is left of the limit now that the title is at level 0, measured rather than
                  wished for: seven pages deep, or fewer where the deepest bodies carry headings
                  of their own. The overflow is silent - asciidoctor writes <h7>, an unknown
                  inline element, and reports nothing, which this render would otherwise fail on."""
            def root = chain(7, NO_HEADING)

        when:
            def rendered = render(root, true)

        then:
            rendered.contains('<h7')
    }

    def 'a body that does not start at the first level jumps one, and asciidoctor says so'() {
        given: """What the move to a level-0 title costs, measured on the pages it was measured
                  against: a Confluence page whose headings start at the second size - which most
                  of the MAVEN space does - reaches pandoc as level 2, and sits under a title at
                  level 0 with level 1 skipped. Nothing is lost and the heading renders where it
                  was written, but where the old level-1 title happened to stand right above such
                  a body the render was silent, and now it is not. Promoting a page's body so that
                  its shallowest heading is level 1 is the follow-up, and it would hand back the
                  levels such a page wastes as well - see docs/decisions.adoc."""
            chain(1, '=== Two levels down\n\nalpha\n')

        when:
            def rendered = renderReporting(directory.resolve('Level_0.adoc'))

        then: 'the heading is where it was written, below the title'
            rendered.contains('<h1>Level 0</h1>')
            rendered.contains('<h3 id="_two_levels_down">Two levels down</h3>')

        and: 'and the jump is reported, which is the part that is new'
            rendered.contains('section title out of sequence: expected level 1, got level 2')
    }

    /**
     * A chain of pages, each the only child of the one before, written as writePage writes them:
     * the export's own file header, a body, and the export's own include of the child.
     *
     * @return the root page, which is where a render of the whole tree starts
     */
    private Path chain(int pages, String body) {
        Map<String, Map<String, Object>> tree = [:]
        (0..<pages).each { index ->
            tree[id(index)] = [title       : "Level $index".toString(),
                               filename    : name(index),
                               adocFilename: name(index),
                               // "0" is what the walk records as the parent of the root.
                               parentId    : id(index - 1)]
        }
        (0..<pages).each { index ->
            List<String> folders = (0..<index).collect { name(it) }
            Path file = directory.resolve((folders + [name(index) + '.adoc']).join('/'))
            Files.createDirectories(file.parent)
            Files.writeString(file,
                    ConfluenceConverter.fileHeader(tree[id(index)], name(index), folders, folders)
                            + body
                            + '\n\n'
                            + ConfluenceConverter.childIncludes(id(index),
                                    index + 1 < pages ? [id(index + 1)] : [], tree))
        }
        return directory.resolve(name(0) + '.adoc')
    }

    private static String id(int index) {
        return String.valueOf(index + 1)
    }

    private static String name(int index) {
        return "Level_$index"
    }

    /**
     * The rendered page, and nothing asciidoctor had to say about it: without --failure-level it
     * exits zero on an error as well, which is how a level-0 heading in the wrong place - or a
     * style block read inside the document header - stays invisible to a test that only looks at
     * the output.
     */
    private String render(Path source, boolean includeChildren = false) {
        def (String rendered, int status) = asciidoctor(source, includeChildren)
        assert !rendered.contains('asciidoctor:')
        assert status == 0
        return rendered
    }

    /** The same render, with what asciidoctor said about it left in. */
    private String renderReporting(Path source, boolean includeChildren = false) {
        return asciidoctor(source, includeChildren).first()
    }

    /** Embedded, which is how a microsite renders an exported page. */
    private List asciidoctor(Path source, boolean includeChildren) {
        List<String> command = ['asciidoctor', '-s', '--failure-level=WARN']
        if (includeChildren) {
            command += ['-a', 'includeChildren']
        }
        Process process = new ProcessBuilder(command + ['-o', '-', source.toString()])
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        return [rendered, process.waitFor()]
    }
}
