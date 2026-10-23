package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States when an attachment in Confluence is one this publisher may write over.
 *
 * The question is not "has it changed" but "has somebody else touched it since we last wrote it".
 * Two things can answer it: the revision an export took the file from, and the hash an upload of
 * ours leaves in the version comment. Where neither answers, the file is somebody else's.
 */
class AttachmentProvenanceSpec extends Specification {

    private static final String OUR_HASH = 'a937de4e4d3a556d0b8886d7ecb5e0c5'

    def 'the revision an export came from is ours to write'() {
        given: 'nobody has written it since the export, whoever wrote it before'
            def provenance = new AttachmentProvenance(3, 3, '')

        expect:
            provenance.isOurs()
    }

    def 'a revision past the one we exported from is not ours'() {
        given: '''measured: the draw.io app writes "draw.io diagram" as its version comment, which
                  carries no hash - so the comment alone can never recognise its edit'''
            def provenance = new AttachmentProvenance(3, 4, '')

        expect:
            !provenance.isOurs()
    }

    def 'a revision carrying our hash is ours, however far past the export it is'() {
        given: 'only an upload of this publisher puts a hash in the comment'
            def provenance = new AttachmentProvenance(3, 9, OUR_HASH)

        expect: 'our own upload moved the version on, and refusing that would refuse every update'
            provenance.isOurs()
    }

    def 'a file no export wrote is ours only where we wrote the revision'() {
        expect: 'an attachment created in Confluence has no version to compare against'
            new AttachmentProvenance(AttachmentName.NO_VERSION, 1, OUR_HASH).isOurs()
            !new AttachmentProvenance(AttachmentName.NO_VERSION, 1, '').isOurs()
    }

    def 'a version Confluence does not report answers nothing'() {
        expect: 'unknown means not ours, never the other way round'
            !new AttachmentProvenance(3, AttachmentProvenance.UNKNOWN_VERSION, '').isOurs()
            !new AttachmentProvenance(AttachmentName.NO_VERSION,
                    AttachmentProvenance.UNKNOWN_VERSION, '').isOurs()
    }

    def 'an earlier revision than the export is not the one we exported from'() {
        given: 'an attachment deleted and attached again starts counting from one'
            def provenance = new AttachmentProvenance(4, 1, '')

        expect:
            !provenance.isOurs()
    }

    def 'content Confluence already holds is recognised by the hash in the comment'() {
        expect:
            new AttachmentProvenance(3, 3, OUR_HASH).holdsOurContent(OUR_HASH)
            !new AttachmentProvenance(3, 3, OUR_HASH).holdsOurContent('something else')

        and: 'a comment nobody of ours wrote holds no hash, so it never counts as unchanged'
            !new AttachmentProvenance(3, 3, '').holdsOurContent(OUR_HASH)
    }
}
