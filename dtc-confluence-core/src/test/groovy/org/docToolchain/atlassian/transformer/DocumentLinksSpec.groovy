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
                ['decisions': 'Architecture', '_end_to_end': 'Architecture'],
                ['building-blocks': 'Building Blocks'])
            .build()
    }

    private static DocumentLinks.Resolution resolve(String href) {
        return index().resolve('/docs/build/html5/manual.html', href)
    }

    def 'a link with no fragment lands on the other document first page'() {
        expect:
            resolve('architecture.html').target.get() == new DocumentLinks.Target('Architecture', '')
    }

    def 'a fragment that is an anchor keeps the anchor'() {
        expect:
            resolve('architecture.html#decisions').target.get() ==
                new DocumentLinks.Target('Architecture', 'decisions')
    }

    def 'a fragment that became a page of its own points at that page, without an anchor'() {
        given: 'there is nothing to anchor to: the heading is the page'

        expect:
            resolve('architecture.html#building-blocks').target.get() ==
                new DocumentLinks.Target('Building Blocks', '')
    }

    def 'a resolved link is reported as having nothing wrong with it'() {
        when:
            def resolution = resolve('architecture.html#decisions')

        then:
            resolution.namesAPublishedDocument()
            !resolution.anchorIsMissing()
            resolution.nearestAnchor().isEmpty()
    }

    def 'a document this run does not publish is no target'() {
        when:
            def resolution = resolve('glossary.html')

        then:
            !resolution.namesAPublishedDocument()
            resolution.target.empty

        and: 'and is not mistaken for a missing anchor, which is the other thing that goes wrong'
            !resolution.anchorIsMissing()
    }

    def 'a fragment the other document does not have still finds the document'() {
        given: '''The document is published; only the spot within it is not there. The page is
                  where the reader was headed, and unlike the link as written it works.'''

        when:
            def resolution = resolve('architecture.html#nowhere')

        then:
            resolution.target.get() == new DocumentLinks.Target('Architecture', '')

        and: 'said plainly, so the author can see what the link asked for'
            resolution.anchorIsMissing()
            resolution.missingAnchor() == 'nowhere'

        and: 'with nothing to suggest, because nothing close exists'
            resolution.nearestAnchor().isEmpty()
    }

    def 'an extension left on the anchor is recognised as the near miss it is'() {
        given: '''xref:architecture#decisions.adoc[] puts the extension behind the '#', where it
                  becomes part of the anchor. AsciiDoctor passes that through without a word.'''

        when:
            def resolution = resolve('architecture.html#decisions.adoc')

        then:
            resolution.anchorIsMissing()
            resolution.nearestAnchor() == 'decisions'
    }

    def 'a heading that became a page of its own is suggested the same way'() {
        expect:
            resolve('architecture.html#building-blocks.html').nearestAnchor() == 'building-blocks'
    }

    def 'a hyphen where AsciiDoctor generated an underscore is suggested'() {
        given: '''AsciiDoctor joins the words of a generated heading id with '_' unless
                  :idseparator: says otherwise, and a hand-written reference tends to hyphenate
                  what the document underscored. Nothing in the document shows the difference.'''

        when:
            def resolution = resolve('architecture.html#_end-to-end')

        then:
            resolution.anchorIsMissing()
            resolution.nearestAnchor() == '_end_to_end'

        and: 'and nothing is said about extensions, because there is none to say it about'
            resolution.misplacedExtension().isEmpty()
    }

    def 'the other direction is suggested just as well'() {
        expect: 'an underscore where the document hyphenated'
            resolve('architecture.html#building_blocks').nearestAnchor() == 'building-blocks'
    }

    def 'both mistakes at once still find the anchor'() {
        expect:
            resolve('architecture.html#_end-to-end.adoc').nearestAnchor() == '_end_to_end'
    }

    def 'an extension on the anchor is reported even with nothing to suggest'() {
        given: 'it is wrong on its own, whether or not the right anchor can be guessed'

        when:
            def resolution = resolve('architecture.html#nothing-like-it.adoc')

        then:
            resolution.misplacedExtension() == '.adoc'
            resolution.nearestAnchor().isEmpty()
    }

    def 'an ambiguous near miss is no suggestion at all'() {
        given: 'two anchors a step apart leave nothing to recommend'
            def links = DocumentLinks.of()
                .add('/docs/build/html5/architecture.html', 'Architecture',
                    ['a_b': 'Architecture', 'a-b': 'Architecture'], [:])
                .build()

        expect:
            links.resolve('/docs/build/html5/manual.html', 'architecture.html#a-b-').nearestAnchor()
                .isEmpty()
    }

    def 'an anchor that merely ends in a dotted word is not second-guessed'() {
        given: 'an anchor may carry a dot of its own, and inventing one from it would mislead'

        expect:
            resolve('architecture.html#decisions.tex').nearestAnchor().isEmpty()
            resolve('architecture.html#nothing-like-it.adoc').nearestAnchor().isEmpty()
    }

    def 'a link is resolved against the linking document, not the working directory'() {
        given: 'two documents need not sit in one directory'
            def links = DocumentLinks.of()
                .add('/docs/build/html5/ref/api.html', 'The API', [:], [:])
                .build()

        expect:
            links.resolve('/docs/build/html5/manual.html', 'ref/api.html')
                .target.get().pageTitle() == 'The API'

        and: 'including upwards'
            links.resolve('/docs/build/html5/guide/intro.html', '../ref/api.html')
                .target.get().pageTitle() == 'The API'
    }

    def 'a run with nothing collected resolves nothing'() {
        expect:
            DocumentLinks.NONE.empty
            !DocumentLinks.NONE.resolve('a.html', 'b.html').namesAPublishedDocument()
    }
}
