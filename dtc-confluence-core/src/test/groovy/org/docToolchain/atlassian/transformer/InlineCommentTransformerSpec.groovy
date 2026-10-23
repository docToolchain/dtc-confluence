package org.docToolchain.atlassian.transformer

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import spock.lang.Specification

/**
 * States that a publish writes back the anchor of an inline comment.
 *
 * A publish replaces the whole body, so a comment's anchor only survives if this puts it there.
 * The fixtures are what Asciidoctor renders for the roles the export writes, double space in the
 * class attribute included - that is pandoc's spelling of two roles coming back through the other
 * converter.
 */
class InlineCommentTransformerSpec extends Specification {

    private static final String REF = '6cc14366-cefd-4ae3-af80-9f5839ca348c'

    private static org.jsoup.nodes.Element bodyOf(String html) {
        def dom = Jsoup.parse("<div>${html}</div>", '', Parser.xmlParser())
        dom.outputSettings().prettyPrint(false)
        return dom.selectFirst('div')
    }

    private static String transform(String html) {
        def body = bodyOf(html)
        new InlineCommentTransformer().transformInlineComments(body)
        return body.html()
    }

    def 'a marked span becomes the element that anchors the comment'() {
        when: 'what Asciidoctor renders for the two roles the export wrote'
            def storage = transform('<p>Probe: <span class="inline-comment-marker  ' +
                    "comment-ref-${REF}\">kann ein Publish eine Kommentar-Markierung schreiben" +
                    '</span>?</p>')

        then: 'the marker, with the ref Confluence issued for that comment'
            storage.contains("<ac:inline-comment-marker ac:ref=\"${REF}\">")

        and: 'around the words it was made on'
            storage.contains('<ac:inline-comment-marker ac:ref="' + REF + '">' +
                    'kann ein Publish eine Kommentar-Markierung schreiben' +
                    '</ac:inline-comment-marker>?')
    }

    def 'the roles go with the tag'() {
        when:
            def storage = transform('<p><span class="inline-comment-marker comment-ref-' + REF +
                    '">marked</span></p>')

        then: 'Confluence reads nothing off a marker but its ref, and a class attribute is noise'
            !storage.contains('class=')
    }

    def 'markup inside the marked words survives'() {
        when:
            def storage = transform('<p><span class="inline-comment-marker comment-ref-' + REF +
                    '">a <strong>bold</strong> claim</span></p>')

        then:
            storage.contains('<ac:inline-comment-marker ac:ref="' + REF + '">' +
                    'a <strong>bold</strong> claim</ac:inline-comment-marker>')
    }

    def 'one comment may anchor several places'() {
        when: 'two spans naming one comment, which is how a split anchor comes back'
            def storage = transform('<p><span class="inline-comment-marker comment-ref-498c3ceb">' +
                    'widely</span> and <span class="inline-comment-marker comment-ref-498c3ceb">' +
                    'used</span></p>')

        then:
            storage.count('<ac:inline-comment-marker ac:ref="498c3ceb">') == 2
    }

    def 'a marked span with no ref is left as it is'() {
        given: '''what Confluence itself writes for a resolved comment: the role degrades to a
                  plain span and the ref is gone, and a marker without a ref anchors nothing'''
            def html = '<p><span class="inline-comment-marker">resolved</span></p>'

        expect:
            transform(html) == html
    }

    def 'a ref that is not one is refused'() {
        when:
            def storage = transform("<p><span class=\"inline-comment-marker comment-ref-${ref}\">" +
                    'marked</span></p>')

        then: 'no anchor is written, rather than one built from whatever stood there'
            !storage.contains('ac:inline-comment-marker')

        and: 'and the marked words stay'
            storage.contains('marked')

        where: 'nothing behind the prefix, and punctuation a ref never has'
            ref << ['', '-leading-hyphen', 'a.b', 'a:b']
    }

    def 'a span that is not a comment anchor is left alone'() {
        given: 'a role of the same shape on a span that was never a marker'
            def html = '<p><span class="comment-ref-' + REF + '">plain</span></p>'

        expect:
            transform(html) == html
    }

    def 'a body without comment anchors is left alone'() {
        given:
            def html = '<p>plain</p>'

        expect:
            transform(html) == html
    }
}
