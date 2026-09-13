package org.docToolchain.atlassian.confluence.page

import org.docToolchain.util.ContentHash
import spock.lang.Specification

/**
 * Restores the intent of the @Ignore'd spec that was deleted with the dead
 * org.docToolchain.scripts.asciidoc2confluence twin: table of contents generation, its
 * replacements, and extra page content. Those behaviours were described there and covered nowhere.
 */
class PageDecoratorSpec extends Specification {

    private static final String BODY = '<p>the page body</p>'

    private static PageDecorator decorator(Map options = [:]) {
        return new PageDecorator(
            options.disabled ?: false,
            options.extra as String,
            options.contents as String,
            options.children as String)
    }

    def 'by default a page gets a table of contents and a list of its children'() {
        when:
            def content = decorator().decorate(BODY)

        then:
            content.contains('<ac:structured-macro ac:name="toc"/>')
            content.contains('<ac:structured-macro ac:name="children">')
            content.contains('<ac:parameter ac:name="sort">creation</ac:parameter>')

        and: 'the contents come before the body and the children after it'
            content.indexOf('toc') < content.indexOf('the page body')
            content.indexOf('children') > content.indexOf('the page body')
    }

    def 'disabling the table of contents removes the children list as well'() {
        when:
            def content = decorator(disabled: true).decorate(BODY)

        then:
            !content.contains('ac:name="toc"')
            !content.contains('ac:name="children"')

        and: 'the body and the hash remain'
            content.contains(BODY)
            content.contains('hash: #')
    }

    def 'a configured table of contents replaces the default macro'() {
        when:
            def content = decorator(contents: '<p><h1 id="custom">My ToC</h1></p>').decorate(BODY)

        then:
            content.contains('id="custom"')
            !content.contains('ac:name="toc"')
    }

    def 'a configured children table replaces the default macro'() {
        when:
            def content = decorator(children: '<p><ac:structured-macro ac:name="foo"/></p>').decorate(BODY)

        then:
            content.contains('ac:name="foo"')
            !content.contains('ac:name="children"')
    }

    def 'extra page content is placed above the body'() {
        when:
            def content = decorator(extra: '<p id="extra">HELLO WORLD</p>').decorate(BODY)

        then:
            content.contains('id="extra"')
            content.indexOf('HELLO WORLD') < content.indexOf('the page body')
    }

    def 'extra page content survives with the table of contents disabled'() {
        when:
            def content = decorator(disabled: true, extra: '<p id="extra">still here</p>').decorate(BODY)

        then:
            content.contains('still here')
            !content.contains('ac:name="toc"')
    }

    def 'the hash covers the body alone, so decoration changes do not look like content changes'() {
        when:
            def plain = decorator().decorate(BODY)
            def decorated = decorator(extra: '<p>something else entirely</p>').decorate(BODY)

        then: 'republishing an untouched document still writes nothing'
            plain.contains("hash: #${ContentHash.md5(BODY)}#")
            decorated.contains("hash: #${ContentHash.md5(BODY)}#")
    }

    def 'a different body yields a different hash'() {
        expect:
            decorator().decorate('<p>one</p>') != decorator().decorate('<p>two</p>')
    }

    def 'an empty configured value falls back to the default rather than emitting nothing'() {
        when:
            def content = decorator(contents: '', children: '', extra: '').decorate(BODY)

        then:
            content.contains('ac:name="toc"')
            content.contains('ac:name="children"')
    }
}
