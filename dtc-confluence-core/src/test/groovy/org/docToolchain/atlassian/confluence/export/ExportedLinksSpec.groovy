package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * States what becomes of an ac:link, and above all of one whose target this export does not hold.
 *
 * Such a link used to lose its destination without a word: the element stayed in the document,
 * whichever converter ran next kept its text alone, and "see here" was left standing for nothing.
 * Of the 173 ac:link elements in the 136 pages of the Maven space, 82 name a page by its title -
 * and a page outside the export is the usual case, not the exception.
 *
 * The fixtures are the links as they stand on cwiki.apache.org, so these say what a page a human
 * wrote keeps and what it loses.
 */
class ExportedLinksSpec extends Specification {

    /** Where the measured pages are served, which is what a link out of the export points at. */
    private static final String SITE = 'https://cwiki.apache.org/confluence'

    private static final Map SPACE = [key: 'MAVEN', name: 'Maven']
    private static final Map NO_USERS = [:]
    private static final Map NO_ATTACHMENTS = [:]

    /** The page the measured links stand on, and one further page of the same export. */
    private static final Map PAGES = [
        '181305684': [title       : 'Maven Ecosystem Cleanup',
                      filename    : 'Maven_Ecosystem_Cleanup',
                      adocFilename: 'Maven_Ecosystem_Cleanup'],
        '191332822': [title       : 'Maven Core Cleanup',
                      filename    : 'Maven_Core_Cleanup',
                      adocFilename: 'Maven_Core_Cleanup',
                      parentId    : '181305684']]

    /** The link to "Baseline Upgrade to Maven 3.5.4" as Maven Ecosystem Cleanup carries it. */
    private static final String OUTSIDE_THE_EXPORT =
        '<ac:link><ri:page ri:content-title="Baseline Upgrade to Maven 3.5.4"/>' +
            '<ac:plain-text-link-body><![CDATA[see here]]></ac:plain-text-link-body></ac:link>'

    private ConfluenceConverter converter = new ConfluenceConverter()

    def setup() {
        converter.confluenceBaseUrl = SITE
    }

    private ConfluenceConverter.ConvertedBody convert(String storage) {
        return converter.fixBody('181305684', storage, NO_USERS, PAGES, NO_ATTACHMENTS, SPACE)
    }

    def 'a link to a page outside the export keeps its destination'() {
        given: '''the link on Maven Ecosystem Cleanup to Baseline Upgrade to Maven 3.5.4, a page
                  of the same space that an export of that subtree does not hold. It exported as
                  the bare words "see here".'''

        when:
            def converted = convert(OUTSIDE_THE_EXPORT)

        then: '''a URL into the Confluence it was read from, in the shape that server answers
                 with a redirect to the canonical page'''
            converted.html().contains(
                '<a href="' + SITE + '/display/MAVEN/Baseline+Upgrade+to+Maven+3.5.4">see here</a>')

        and: 'and nothing reported, because nothing was lost'
            converted.unknownTags().isEmpty()
    }

    def 'a link into another space is not read as a link into this one'() {
        given: '''the one link measured that names a space of its own, given here a title that a
                  page of this export carries too: two spaces may both hold a page of that name,
                  so the title alone says nothing about where the link goes'''
            def storage = '<ac:link><ri:page ri:space-key="BACKGROUND" ' +
                'ri:content-title="Maven Core Cleanup"/>' +
                '<ac:plain-text-link-body><![CDATA[Background]]></ac:plain-text-link-body></ac:link>'

        when:
            def html = convert(storage).html()

        then: 'the space the link names'
            html.contains(
                '<a href="' + SITE + '/display/BACKGROUND/Maven+Core+Cleanup">Background</a>')

        and: 'rather than the page of that name in this one'
            !html.contains('xref:')
    }

    def 'a page link with no text of its own reads as the title of the page'() {
        given: '46 of the 82 measured links have this shape: a target and no body at all'
            def storage =
                '<ac:link><ri:page ri:content-title="Maven 3.x and site plugin"/></ac:link>'

        expect: 'the title, which is what Confluence itself shows for such a link'
            convert(storage).html().contains('<a href="' + SITE
                + '/display/MAVEN/Maven+3.x+and+site+plugin">Maven 3.x and site plugin</a>')
    }

    def 'a link to a page of this export is still a cross reference'() {
        given: 'the branch that always worked, which a URL into Confluence must not take over'
            def storage = '<ac:link ac:anchor="part">' +
                '<ri:page ri:content-title="Maven Core Cleanup"/>' +
                '<ac:plain-text-link-body><![CDATA[there]]></ac:plain-text-link-body></ac:link>'

        when:
            def html = convert(storage).html()

        then: 'a path to the file that page was written as, anchor included'
            html.contains('xref:Maven_Ecosystem_Cleanup/Maven_Core_Cleanup.adoc#_part[there]')

        and: 'and no detour through the server for a page that is right here'
            !html.contains(SITE)
    }

    def 'a link to an anchor this page sets points into the document'() {
        given: '''the link on Maven Ecosystem Cleanup to its own "resolver" anchor, one of the
                  8 of 13 measured anchor links that name an anchor the page sets. Its body is
                  the rich-text form, which is what Confluence writes once someone has left an
                  inline comment on a link.'''
            def storage = '<ac:structured-macro ac:name="anchor">' +
                '<ac:parameter ac:name="">resolver</ac:parameter></ac:structured-macro>' +
                '<p><ac:link ac:anchor="resolver"><ac:link-body> ' +
                '<span class="inline-comment-marker">m-artifact-resolver 2.x plans,</span>' +
                '</ac:link-body></ac:link></p>'

        when:
            def converted = convert(storage)

        then: 'spelled as the anchor itself is spelled, underscore included'
            converted.html().contains('<a href="#_resolver">m-artifact-resolver 2.x plans,</a>')

        and: 'and ac:link-body no longer among the tags with no translation'
            converted.unknownTags().isEmpty()
    }

    def 'a link to an anchor the page does not set keeps its destination in Confluence'() {
        given: '''5 of the 13 measured anchor links name an anchor Confluence generated for a
                  heading. The exported document carries the heading but no id of that name, and
                  a reference to an id nothing declares renders as its own name in brackets.'''
            def storage = '<ac:link ac:anchor="Draftingtheplan">' +
                '<ac:plain-text-link-body><![CDATA[the plan]]></ac:plain-text-link-body></ac:link>'

        expect: '''the id Confluence gives such an anchor: the page title and the anchor name,
                   each with its spaces taken out'''
            convert(storage).html().contains('<a href="' + SITE
                + '/display/MAVEN/Maven+Ecosystem+Cleanup'
                + '#MavenEcosystemCleanup-Draftingtheplan">the plan</a>')
    }

    def 'a mention of a user is reported rather than lost without a word'() {
        given: '''53 of the 78 mentions measured have this shape: a userkey and no text at all,
                  so the document was left with nothing where a name stood. The storage format
                  carries no name, and the profile URL is built from one.'''
            def storage =
                '<p><ac:link><ri:user ri:userkey="8aa980874e36a1eb014e36a2c30c772e"/></ac:link></p>'

        when:
            def converted = convert(storage)

        then: 'the summary names the gap, grouped by what the link named its target by'
            converted.unknownTags() == ['ac:link ri:user']

        and: 'and no URL is invented for a user whose name is not known'
            !converted.html().contains('<a ')
    }

    def 'a mention that carries a name keeps the name'() {
        given: 'the other 25, which name the user they mention in the body of the link'
            def storage = '<p><ac:link><ri:user ri:userkey="8aa980874e36a1eb014e36a2cf291190"/>' +
                '<ac:plain-text-link-body><![CDATA[Hervé Boutemy]]>' +
                '</ac:plain-text-link-body></ac:link></p>'

        when:
            def converted = convert(storage)

        then:
            converted.html().contains('Hervé Boutemy')

        and: 'and the link it was is still reported, because the profile it pointed at is gone'
            converted.unknownTags() == ['ac:link ri:user']
    }

    def 'a link outside the export is reported where no Confluence is known'() {
        given: '''a conversion given no base URL, which is how one runs without a server: a URL
                  invented without a host would point nowhere'''
            converter.confluenceBaseUrl = ''

        when:
            def converted = convert(OUTSIDE_THE_EXPORT)

        then: 'the words stay, as they always did'
            converted.html().contains('see here')

        and: 'and the loss is in the summary rather than only in the page'
            converted.unknownTags() == ['ac:link ri:page']
    }

    def 'a link shape the corpus does not contain is reported rather than dropped'() {
        given: '''neither an attachment nor a blog post is linked to anywhere in the 136 pages
                  measured, so neither has a translation here - which is a reason to report them,
                  not a reason to pass them over'''

        expect:
            convert(storage).unknownTags() == [reported]

        where:
            storage                                                              || reported
            '<ac:link><ri:attachment ri:filename="notes.pdf"/>' +
                '<ac:plain-text-link-body><![CDATA[the notes]]>' +
                '</ac:plain-text-link-body></ac:link>'                           || 'ac:link ri:attachment'
            '<ac:link><ri:blog-post ri:content-title="A post"/>' +
                '<ac:plain-text-link-body><![CDATA[a post]]>' +
                '</ac:plain-text-link-body></ac:link>'                           || 'ac:link ri:blog-post'
    }
}
