package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * Keeps the HTML corpus of dtc-confluence-html2adoc equal to what this project really produces.
 *
 * The corpus is the input half of the round trip: the publisher's golden transcripts are the
 * storage format docToolchain writes, and {@link ConfluenceConverter#fixBody} is what an export
 * hands on - plain HTML plus placeholder tokens, with every ac:/ri: macro already translated. That
 * is what the html2adoc converter is fed at runtime, so it is what its tests must be written
 * against; raw storage format never reaches it, and invented HTML would measure nothing.
 *
 * The corpus lives in the other module rather than here because that is where it is read. This
 * spec is its generator and its guard: a change to MacroTranslator or to fixBody turns it red,
 * which says that the corpus - and the inventory beside it - no longer describes the input.
 *
 * Run with {@code -Dcorpus.update=true} to rewrite the corpus after an intended change, and review
 * the diff together with INVENTORY.md.
 */
class Html2AdocCorpusSpec extends Specification {

    private static final String TRANSCRIPTS = 'src/test/resources/publisher'

    /** Where the corpus is read: the test resources of the module under measurement. */
    private static final String CORPUS =
            '../dtc-confluence-html2adoc/src/test/resources/corpus'

    /** The transcripts to convert, in the order their pages are numbered. */
    private static final List<String> SOURCES =
            ['smoke-subpages-0.txt', 'smoke-subpages-1.txt', 'smoke-subpages-2.txt']

    /** What the transcript records a page creation as. */
    private static final java.util.regex.Pattern CREATE =
            ~/^CREATE page '(.*)' space=(\S+) parent=(\S+) comment=/

    /** The first id the recording client in PublisherGoldenFileSpec hands out. */
    private static final int FIRST_PAGE_ID = 1000

    /** One page of a transcript: what it was called, where it hung, and its storage format. */
    private static class Page {
        String id
        String title
        String parentId
        String space
        StringBuilder body = new StringBuilder()
    }

    def 'the corpus is the HTML an export of our own pages hands to html2adoc'() {
        given: 'every page of every transcript, deduplicated by its storage format'
            Map<String, String> corpus = [:]
            Map<String, String> firstNameOfBody = [:]
            SOURCES.each { String source ->
                List<Page> pages = parse(new File("${TRANSCRIPTS}/${source}"))
                Map<String, Map<String, Object>> pageMap = pageMap(pages)
                pages.each { Page page ->
                    String name = "${source - '.txt'}-${PageNaming.sanitizeFilename(page.title)}"
                    String body = page.body.toString()
                    // A subpage split changes the pages a document is cut into, not all of them:
                    // the root of smoke-subpages-1 and -2 is the same page, and so is Second Page.
                    // Converting a body twice would only weight the inventory towards whatever
                    // happens to appear in a page that survived a split unchanged.
                    String seen = firstNameOfBody.putIfAbsent(body, name)
                    if (seen == null) {
                        corpus[name] = convert(page, body, pageMap)
                    }
                }
            }

        expect: 'what is checked in'
            corpus.every { String name, String html -> matchesCorpus(name, html) }

        and: 'and nothing else, so that a page that stopped being generated does not linger'
            new File(CORPUS).list({ dir, name -> name.endsWith('.html') } as FilenameFilter)
                    .toList().toSorted() == corpus.keySet().collect { "${it}.html" }.toSorted()
    }

    /** @return the pages of one transcript, numbered as the recording client numbered them */
    private List<Page> parse(File transcript) {
        List<Page> pages = []
        transcript.eachLine('UTF-8') { String line ->
            def create = CREATE.matcher(line)
            if (create.find()) {
                pages << new Page(id: String.valueOf(FIRST_PAGE_ID + pages.size()),
                        title: create.group(1), space: create.group(2),
                        parentId: create.group(3))
            } else if (line.startsWith('    | ')) {
                Page page = pages.last()
                if (page.body.length() > 0) {
                    page.body.append('\n')
                }
                page.body.append(line.substring('    | '.length()))
            }
        }
        return pages
    }

    /**
     * The page map an export of these pages would hold, so that a link between them is rewritten
     * the way it is rewritten in production rather than left as an untranslated ac:link.
     *
     * The ancestor of the topmost page is the ancestorId the publish hung the tree under. It is no
     * part of the export, and a page map that named it would leave every page without folders, so
     * the top page is given no parent - which is what an export rooted there sees.
     */
    private Map<String, Map<String, Object>> pageMap(List<Page> pages) {
        Set<String> ids = pages*.id as Set
        Map<String, Map<String, Object>> pageMap = [:]
        pages.each { Page page ->
            String filename = PageNaming.sanitizeFilename(page.title)
            pageMap[page.id] = [
                    title       : page.title,
                    filename    : filename,
                    adocFilename: filename,
                    parentId    : ids.contains(page.parentId) ? page.parentId : '0',
            ]
        }
        return pageMap
    }

    private String convert(Page page, String body, Map<String, Map<String, Object>> pages) {
        return new ConfluenceConverter()
                .fixBody(page.id, body, [:], pages, [:], [key: page.space, name: page.space])
                .html()
    }

    private boolean matchesCorpus(String name, String html) {
        File file = new File(CORPUS, "${name}.html")
        if (System.getProperty('corpus.update') == 'true' || !file.exists()) {
            file.parentFile.mkdirs()
            file.setText(html, 'UTF-8')
            println "corpus file written: ${file}"
        }
        assert html == file.getText('UTF-8')
        return true
    }
}
