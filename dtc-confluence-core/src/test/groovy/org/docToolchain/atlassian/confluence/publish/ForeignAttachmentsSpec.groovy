package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States what is read out of confluence.foreignAttachments, and what an absent or unusable setting
 * amounts to.
 */
class ForeignAttachmentsSpec extends Specification {

    def 'the setting names what happens to an attachment somebody else wrote'() {
        expect:
            ForeignAttachments.from(configured) == choice

        where:
            configured    || choice
            'fail'        || ForeignAttachments.FAIL
            'skip'        || ForeignAttachments.SKIP
            'overwrite'   || ForeignAttachments.OVERWRITE
            'SKIP'        || ForeignAttachments.SKIP
            '  skip  '    || ForeignAttachments.SKIP
    }

    def 'nothing configured refuses, which is the choice that loses nothing'() {
        expect:
            ForeignAttachments.from(null) == ForeignAttachments.FAIL
    }

    def 'a setting that names nothing refuses as well, rather than guessing'() {
        expect: 'a typo must not silently become the choice that overwrites somebody work'
            ForeignAttachments.from('overwrit') == ForeignAttachments.FAIL
            ForeignAttachments.from(true) == ForeignAttachments.FAIL
    }
}
