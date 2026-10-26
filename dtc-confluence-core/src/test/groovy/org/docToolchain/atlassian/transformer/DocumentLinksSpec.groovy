package org.docToolchain.atlassian.transformer

import spock.lang.Specification

/**
 * States how a link out of one document finds the page another document became.
 *
 * The link says a file name, Confluence knows only titles, and the two are connected by nothing
 * but this run having published both.
 */
class DocumentLinksSpec extends Specification {

    private static DocumentLinks index() {
        return DocumentLinks.of()
            .add('/docs/build/html5/manual.html', 'The Manual',
                ['install': 'The Manual'], [:])
            .add('/docs/build/html5/architecture.html', 'Architecture',
                ['decisions': 'Architecture'], ['building-blocks': 'Building Blocks'])
            .build()
    }

    def 'a link with no fragment lands on the other document first page'() {
        expect:
            index().targetOf('/docs/build/html5/manual.html', 'architecture.html')
                .get() == new DocumentLinks.Target('Architecture', '')
    }

    def 'a fragment that is an anchor keeps the anchor'() {
        expect:
            index().targetOf('/docs/build/html5/manual.html', 'architecture.html#decisions')
                .get() == new DocumentLinks.Target('Architecture', 'decisions')
    }

    def 'a fragment that became a page of its own points at that page, without an anchor'() {
        given: 'there is nothing to anchor to: the heading is the page'

        expect:
            index().targetOf('/docs/build/html5/manual.html', 'architecture.html#building-blocks')
                .get() == new DocumentLinks.Target('Building Blocks', '')
    }

    def 'a document this run does not publish is no target'() {
        expect:
            index().targetOf('/docs/build/html5/manual.html', 'glossary.html').empty
    }

    def 'a fragment the other document does not have is no target either'() {
        given: '''Landing on its first page would send the reader somewhere the link did not name,
                  which reads as an answer rather than as the broken link it is.'''

        expect:
            index().targetOf('/docs/build/html5/manual.html', 'architecture.html#nowhere').empty
    }

    def 'a link is resolved against the linking document, not the working directory'() {
        given: 'two documents need not sit in one directory'
            def links = DocumentLinks.of()
                .add('/docs/build/html5/ref/api.html', 'The API', [:], [:])
                .build()

        expect:
            links.targetOf('/docs/build/html5/manual.html', 'ref/api.html')
                .get().pageTitle() == 'The API'

        and: 'including upwards'
            links.targetOf('/docs/build/html5/guide/intro.html', '../ref/api.html')
                .get().pageTitle() == 'The API'
    }

    def 'a run with nothing collected resolves nothing'() {
        expect:
            DocumentLinks.NONE.empty
            DocumentLinks.NONE.targetOf('a.html', 'b.html').empty
    }
}
