package org.docToolchain.atlassian.confluence.export

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Runs both {@link HtmlToAsciidoc} implementations over the whole corpus.
 *
 * <p>The two outputs are not compared with each other, because they differ on purpose: pinning
 * pandoc's output as the expected value would make every improvement of the native converter a
 * test failure, and would state that pandoc is right where the report finds that it is not.</p>
 *
 * <p>What is pinned instead is how many blocks of each kind the native converter writes per page,
 * counted the way AsciiDoc reads them - at the start of a line. That is what the report counts, so
 * a drift that leaves the output non-empty but no longer matching the report turns this red rather
 * than passing quietly. A count that changes here is not by itself a defect: it means the report
 * has to be re-read against the new output and the number brought over.</p>
 *
 * <p>The comparison itself is in {@code docs/html2adoc-poc.adoc}. This spec writes what each
 * converter produced to {@code target/html2adoc-comparison}, so that the report can be checked
 * against a run rather than believed.</p>
 */
class ConverterComparisonSpec extends Specification {

    /** The corpus, in the module it is read from; see its INVENTORY.md for where it comes from. */
    private static final File CORPUS =
            new File('../html2adoc/src/test/resources/corpus')

    /** Where both answers are left for the report to cite. Under target: evidence, not a fixture. */
    private static final File EVIDENCE = new File('target/html2adoc-comparison')

    /**
     * What the markup of each kind of block looks like where AsciiDoc reads it. All but the
     * cross-reference are anchored to the start of a line, because that is the only place the
     * markup is markup; an unanchored count would be satisfied by a line that shows it as text.
     */
    private static final Map<String, Pattern> BLOCKS = [
            heading    : ~/(?m)^=+ \S/,
            title      : ~/(?m)^\.\S/,
            listItem   : ~/(?m)^\* \S/,
            sourceBlock: ~/(?m)^\[source, \w+]$/,
            tableEdge  : ~/(?m)^[|!]===$/,
            blockImage : ~/(?m)^image::/,
            crossRef   : ~/xref:/,
    ]

    /**
     * The counts the report was written from, per corpus page. {@code blockImage} is zero
     * throughout: the report records that the native converter writes no block image yet, and this
     * is where that claim is kept honest in both directions.
     */
    private static final Map<String, Map<String, Integer>> EXPECTED = [
            'smoke-subpages-0-docToolchain_Confluence_Smoke_Test':
                    [heading: 3, title: 1, listItem: 3, sourceBlock: 1, tableEdge: 2, blockImage: 0, crossRef: 5],
            'smoke-subpages-1-docToolchain_Confluence_Smoke_Test':
                    [heading: 0, title: 0, listItem: 0, sourceBlock: 0, tableEdge: 0, blockImage: 0, crossRef: 0],
            'smoke-subpages-1-First_Page':
                    [heading: 1, title: 1, listItem: 3, sourceBlock: 1, tableEdge: 0, blockImage: 0, crossRef: 2],
            'smoke-subpages-1-Second_Page':
                    [heading: 0, title: 0, listItem: 0, sourceBlock: 0, tableEdge: 2, blockImage: 0, crossRef: 3],
            'smoke-subpages-2-First_Page':
                    [heading: 0, title: 1, listItem: 3, sourceBlock: 0, tableEdge: 0, blockImage: 0, crossRef: 2],
            'smoke-subpages-2-Subsection':
                    [heading: 0, title: 0, listItem: 0, sourceBlock: 1, tableEdge: 0, blockImage: 0, crossRef: 0],
    ]

    @TempDir
    Path work

    def setupSpec() {
        EVIDENCE.mkdirs()
    }

    /** @return every corpus file, in a stable order so that a failure names the same page twice */
    static List<File> corpus() {
        return CORPUS.listFiles({ File file -> file.name.endsWith('.html') } as FileFilter)
                .toList().sort { it.name }
    }

    static boolean pandocIsInstalled() {
        try {
            return new ProcessBuilder('pandoc', '--version').start().waitFor() == 0
        } catch (IOException ignored) {
            return false
        }
    }

    def 'the corpus and the pinned counts name the same pages'() {
        expect: 'a page added to the corpus is counted too, rather than measured by nothing'
            corpus().collect { it.name - '.html' }.toSorted() == EXPECTED.keySet().toSorted()
    }

    def 'the native converter writes the blocks the report counted for #page.name'() {
        given:
            File adoc = work.resolve('native.adoc').toFile()

        when: 'the step an export performs, in process, placeholders spelled out as afterwards'
            boolean written = new NativeHtmlToAsciidoc().convert(page, adoc)
            String exported = AdocOutput.substitute(adoc.getText('UTF-8'))

        then: 'it did not throw, and it wrote a document rather than an empty file'
            written
            !exported.trim().isEmpty()

        and: 'no placeholder of the translation was left in the document'
            !exported.contains('%%')

        and: 'and each kind of block is there as many times as the report says'
            counts(exported) == EXPECTED[page.name - '.html']

        where:
            page << corpus()
    }

    @Requires({ ConverterComparisonSpec.pandocIsInstalled() })
    def 'pandoc and the native converter both answer #page.name'() {
        when: 'both, over the same input, through the placeholders an export spells out afterwards'
            String pandoc = exportedBy(new PandocHtmlToAsciidoc(), page, 'pandoc')
            String own = exportedBy(new NativeHtmlToAsciidoc(), page, 'native')

        then: 'two documents; where they differ, and which is right, is read from the report'
            !pandoc.trim().isEmpty()
            !own.trim().isEmpty()

        where:
            page << corpus()
    }

    /** @return how many times each kind of block of {@link #BLOCKS} stands in {@code adoc} */
    private static Map<String, Integer> counts(String adoc) {
        return BLOCKS.collectEntries { String kind, Pattern pattern ->
            [kind, (adoc =~ pattern).count]
        }
    }

    /**
     * Converts one corpus page and keeps both halves of the result: what the converter wrote, and
     * what {@link AdocOutput} made of it - which is what the exported document holds.
     *
     * @return the AsciiDoc the export would write for this page
     */
    private String exportedBy(HtmlToAsciidoc converter, File page, String name) {
        String base = page.name - '.html'
        File raw = new File(EVIDENCE, "${base}.${name}.raw.adoc")
        assert converter.convert(page, raw)
        String exported = AdocOutput.substitute(raw.getText('UTF-8'))
        new File(EVIDENCE, "${base}.${name}.adoc").setText(exported, 'UTF-8')
        return exported
    }
}
