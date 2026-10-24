package org.docToolchain.util

import spock.lang.Specification
import spock.lang.Unroll

class ContentHashSpec extends Specification {

    @Unroll
    def 'md5 of #description matches the published test vector'() {
        expect:
            ContentHash.md5(input) == expected

        where:
            description   | input  || expected
            'the empty string' | ''    || 'd41d8cd98f00b204e9800998ecf8427e'
            '"abc"'            | 'abc' || '900150983cd24fb0d6963f7d28e17f72'
    }

    def 'the hash is always 32 hex characters, leading zeros included'() {
        expect: 'a digest whose first byte is zero must not come back shorter'
            (1..500).every {
                def hash = ContentHash.md5("input ${it}")
                hash.length() == 32 && hash ==~ /[0-9a-f]{32}/
            }
    }

    def 'the same content always hashes the same, which is all it is used for'() {
        expect:
            ContentHash.md5('<p>a page body</p>') == ContentHash.md5('<p>a page body</p>')
            ContentHash.md5('<p>a page body</p>') != ContentHash.md5('<p>a different body</p>')
    }

    def 'sha1 reproduces the hash the draw.io app names a pinned view\'s preview after'() {
        given: '''Not a choice of ours. The app asks for <diagram>-<sha1 of the aspect>.png, so
                  this is the only way to read which view such a preview belongs to. Both values
                  read off a real page in the test space.'''

        expect:
            ContentHash.sha1('view-building-blocks') ==
                '3f7fef23c469fa17e86e55c813d4ce1da8b1945d'
            ContentHash.sha1('view-core') == '62167aa2f85e4935ba9c7c2eba7bb76551487513'

        and: 'forty digits, padded where the number is short of them'
            ContentHash.sha1('').length() == 40
    }
}
