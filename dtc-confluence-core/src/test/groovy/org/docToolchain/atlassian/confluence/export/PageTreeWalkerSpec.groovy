package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States what a walk of a page tree collects, and what it does when part of it cannot be read.
 */
class PageTreeWalkerSpec extends Specification {

    private ConfluenceReader reader = Mock(ConfluenceReader)
    private ConfluenceConverter converter = new ConfluenceConverter()

    private static Map page(String id, String title, Map extra = [:]) {
        return [id: id, title: title, space: [key: 'SPACE', name: 'A Space']] + extra
    }

    private PageTreeWalker walker(String stripRegex = '') {
        return new PageTreeWalker(reader, converter, stripRegex)
    }

    def 'a root without children is one page'() {
        given:
            reader.fetchPage('1') >> page('1', 'Only Page')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            def tree = walker().walk('1')

        then:
            tree.pages.keySet() == ['1'] as Set
            tree.pages['1'].title == 'Only Page'
            tree.pages['1'].parentId == '0'
            tree.space == [name: 'A Space', key: 'SPACE', homePage: '1']
    }

    def 'children are followed, and each records the parent it hangs under'() {
        given:
            reader.fetchPage('1') >> page('1', 'Root')
            reader.fetchPage('2') >> page('2', 'First Child')
            reader.fetchPage('3') >> page('3', 'Second Child')
            reader.fetchChildPages('1') >> [[id: '2'], [id: '3']]
            reader.fetchChildPages(_) >> []
            reader.fetchAttachments(_) >> []

        when:
            def tree = walker().walk('1')

        then:
            tree.pages.keySet() == ['1', '2', '3'] as Set
            tree.pages['2'].parentId == '1'
            tree.pages['3'].parentId == '1'

        and: 'their order is the order Confluence gave, which is the order they are written in'
            tree.pages['2'].position == '0'
            tree.pages['3'].position == '1'
            tree.childrenByParent['1'] == ['2', '3']
    }

    def 'a page that cannot be read is reported, and the rest is still exported'() {
        given: 'a tree of a few hundred pages usually holds one whose permissions differ'
            reader.fetchPage('1') >> page('1', 'Root')
            reader.fetchPage('2') >> null
            reader.fetchPage('3') >> page('3', 'Readable')
            reader.fetchChildPages('1') >> [[id: '2'], [id: '3']]
            reader.fetchChildPages(_) >> []
            reader.fetchAttachments(_) >> []

        when:
            def tree = walker().walk('1')

        then:
            tree.pages.keySet() == ['1', '3'] as Set
            tree.unreadable == ['2']
    }

    def 'a root that cannot be read ends the export'() {
        given:
            reader.fetchPage('404') >> null

        when:
            walker().walk('404')

        then: 'unlike a page inside the tree: with no root there is nothing to export'
            def e = thrown(IllegalStateException)
            e.message.contains('404')
    }

    def 'a page reached twice is read once'() {
        given: 'otherwise its children are queued twice, and that does not end'
            reader.fetchPage('1') >> page('1', 'Root')
            reader.fetchChildPages('1') >> [[id: '2'], [id: '2']]
            reader.fetchChildPages('2') >> []
            reader.fetchAttachments(_) >> []

        when:
            def tree = walker().walk('1')

        then: """Counted, not inferred from the result: walking the same page twice writes the
                 same entry twice and leaves a map that looks exactly right."""
            1 * reader.fetchPage('2') >> page('2', 'Child')
            tree.pages.keySet() == ['1', '2'] as Set
    }

    def 'a title becomes a file name that a file system accepts'() {
        given:
            reader.fetchPage('1') >> page('1', 'A Page: with / awkward characters')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            def tree = walker().walk('1')

        then:
            !tree.pages['1'].filename.contains('/')
            !tree.pages['1'].filename.contains(':')
    }

    def 'a prefix regex shortens the adoc name and leaves the original alone'() {
        given: 'the storage format still refers to images by the original name'
            reader.fetchPage('1') >> page('1', 'PROJ XXX Architecture')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            def tree = walker('^PROJ_XXX_').walk('1')

        then:
            tree.pages['1'].filename == 'PROJ_XXX_Architecture'
            tree.pages['1'].adocFilename == 'Architecture'
    }

    def 'contributors are the creator first, without repeats'() {
        given:
            reader.fetchPage('1') >> page('1', 'Root', [history: [
                createdBy   : [displayName: 'Gerd'],
                contributors: [publishers: [users: [
                    [displayName: 'Gerd'], [displayName: 'Someone Else']]]]]])
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> []

        when:
            def tree = walker().walk('1')

        then:
            tree.pages['1'].contributors == ['Gerd', 'Someone Else']
    }

    def 'attachments are collected with the page they hang on'() {
        given:
            reader.fetchPage('1') >> page('1', 'Root')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> [[id: 'att1', title: 'diagram.png',
                                              version: [number: 3],
                                              _links: [download: '/download/attachments/1/diagram.png']]]

        when:
            def tree = walker().walk('1')

        then:
            tree.attachments['att1'].filename == 'diagram.png'
            tree.attachments['att1'].originalFilename == 'diagram.png'
            tree.attachments['att1'].version == '3'
            tree.attachments['att1'].pageId == '1'
            tree.attachments['att1'].downloadUrl == '/download/attachments/1/diagram.png'
    }

    def 'an attachment without a version is version one'() {
        given:
            reader.fetchPage('1') >> page('1', 'Root')
            reader.fetchChildPages('1') >> []
            reader.fetchAttachments('1') >> [[id: 'att1', title: 'a.png']]

        when:
            def tree = walker().walk('1')

        then:
            tree.attachments['att1'].version == '1'
            tree.attachments['att1'].downloadUrl == ''
    }
}
