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

    def 'a name no export wrote is the name, whole'() {
        expect: 'and it carries no version, which is what says nothing here wrote it'
            AttachmentName.of(written).fileName() == written
            AttachmentName.of(written).exportedVersion() == AttachmentName.NO_VERSION

        where:
            written << ['diagram.png', 'dummy-diagram', '_leading.png', '3-diagram.png',
                        '3_', 'v3_diagram.png', '99999999999_too-many-digits.png']
    }
}
