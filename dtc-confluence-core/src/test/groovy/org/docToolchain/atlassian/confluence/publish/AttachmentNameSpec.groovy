package org.docToolchain.atlassian.confluence.publish

import spock.lang.Specification

/**
 * States which part of an exported file name is the attachment's name and which is where it came
 * from.
 *
 * The export writes "<version>_<filename>" because a document may refer to an older revision of a
 * diagram. Confluence knows the file under the name without that prefix, and the draw.io app looks
 * for exactly that name.
 */
class AttachmentNameSpec extends Specification {

    def 'the version an export wrote is read off the name, and is not part of it'() {
        expect:
            AttachmentName.of(written).fileName() == name
            AttachmentName.of(written).exportedVersion() == version

        where:
            written                 || name                 | version
            '3_diagram.png'         || 'diagram.png'        | 3
            '1_overview.png'        || 'overview.png'       | 1
            '147_a.svg'             || 'a.svg'              | 147
            '12_dummy-diagram'      || 'dummy-diagram'      | 12
            '2_my_report.pdf'       || 'my_report.pdf'      | 2
    }

    def 'a merged diagram carries two revisions: the picture it is and the diagram in it'() {
        given: '''The export makes one file of two attachments, and the two stand at revisions of
                  their own - measured on a real page, the app had written the diagram twice and
                  the preview once. One number for both makes the clobber guard refuse the preview
                  nobody has touched, and accept one somebody else has written.'''

        expect:
            AttachmentName.of(written).fileName() == name
            AttachmentName.of(written).exportedVersion() == picture
            AttachmentName.of(written).exportedDiagramVersion() == diagram

        where:
            written             || name            | picture | diagram
            '1-2_arch.png'      || 'arch.png'      | 1       | 2
            '2-2_arch.png'      || 'arch.png'      | 2       | 2
            '12-147_a.png'      || 'a.png'         | 12      | 147
    }

    def 'a name carrying one revision says nothing about a diagram inside it'() {
        given: '''The number means what it means for every other attachment: the revision of the
                  attachment this file is. What revision a diagram inside it came from is then
                  unknown, and unknown must not pass for ours.'''

        expect:
            AttachmentName.of('3_diagram.png').exportedDiagramVersion() == AttachmentName.NO_VERSION
            AttachmentName.of('diagram.png').exportedDiagramVersion() == AttachmentName.NO_VERSION
    }

    def 'a name no export wrote is the name, whole'() {
        expect: 'and it carries no version, which is what says nothing here wrote it'
            AttachmentName.of(written).fileName() == written
            AttachmentName.of(written).exportedVersion() == AttachmentName.NO_VERSION

        where:
            written << ['diagram.png', 'dummy-diagram', '_leading.png', '3-diagram.png',
                        '3_', 'v3_diagram.png', '99999999999_too-many-digits.png']
    }
}
