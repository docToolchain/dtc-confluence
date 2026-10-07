package org.docToolchain.atlassian.confluence.export

import spock.lang.Specification

/**
 * What {@code confluence.export.converter} selects.
 *
 * <p>An unset setting has to mean pandoc, because that is what the export did before there was a
 * choice; a misspelt one has to be refused, because falling back to the default would answer a
 * typo with output that looks right and was produced by the other converter.</p>
 */
class HtmlToAsciidocSpec extends Specification {

    def 'the setting #setting selects #expected.simpleName'() {
        expect:
            HtmlToAsciidoc.named(setting).class == expected

        where:
            setting  || expected
            null     || PandocHtmlToAsciidoc
            ''       || PandocHtmlToAsciidoc
            'pandoc' || PandocHtmlToAsciidoc
            'native' || NativeHtmlToAsciidoc
    }

    def 'a converter nobody implements is refused, and the message says what there is'() {
        when:
            HtmlToAsciidoc.named('asciidoctorj')

        then:
            IllegalStateException e = thrown()
            e.message.contains('asciidoctorj')
            e.message.contains('pandoc')
            e.message.contains('native')
    }
}
