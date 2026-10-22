package org.docToolchain.atlassian.confluence.export

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the child includes do when AsciiDoc reads them, rather than what they say.
 *
 * A specification that compares the include line against a string cannot tell a working attribute
 * from an ignored one: `levelOffset` and `leveloffset` are the same line to a reader and not to
 * the include directive, whose attribute list is matched literally against the names it knows.
 * (Document attributes elsewhere are case-folded; this is a rule about an include's brackets only.)
 * The misspelling was accepted without complaint for as long as it was only ever compared with
 * itself, and every page of an exported tree was included at the level it was written at.
 *
 * This renders instead, so the assertion is about the document the reader gets.
 */
@Requires({ ChildIncludeLevelSpec.asciidoctorIsInstalled() })
class ChildIncludeLevelSpec extends Specification {

    @TempDir
    Path directory

    static boolean asciidoctorIsInstalled() {
        try {
            return new ProcessBuilder('asciidoctor', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def 'a child page is rendered one level below the page that includes it'() {
        given: 'the include line the export writes, taken from the export rather than retyped'
            def pages = ['1': [filename: 'Parent', adocFilename: 'Parent'],
                         '2': [filename: 'Child', adocFilename: 'Child', parentId: '1']]
            def include = ConfluenceConverter.childIncludes('1', ['2'], pages)
                    .readLines().find { it.startsWith('include::') }

        and: 'a parent that includes one child, each written as the export writes it'
            Files.createDirectory(directory.resolve('Parent'))
            Files.writeString(directory.resolve('Parent/Child.adoc'), '== Child page\n\nBody.\n')
            // No :includeChildren: here: the include line is taken bare, without the
            // ifdef that wraps it in the export. That guard is specified in ConfluenceConverterSpec.
            Files.writeString(directory.resolve('Parent.adoc'),
                    '== Parent page\n\n' + include + '\n')

        when:
            def rendered = render(directory.resolve('Parent.adoc'))

        then: 'the parent is where it was written'
            rendered.contains('<h2 id="_parent_page">Parent page</h2>')

        and: 'and the child is below it, not beside it'
            rendered.contains('<h3 id="_child_page">Child page</h3>')
    }

    private String render(Path source) {
        Process process = new ProcessBuilder('asciidoctor', '-s', '-o', '-', source.toString())
                .redirectErrorStream(true).start()
        String rendered = process.inputStream.getText('UTF-8')
        assert process.waitFor() == 0
        return rendered
    }
}
