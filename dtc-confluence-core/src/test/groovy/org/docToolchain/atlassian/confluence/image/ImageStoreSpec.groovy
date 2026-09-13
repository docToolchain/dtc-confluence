package org.docToolchain.atlassian.confluence.image

import org.docToolchain.util.ContentHash
import org.docToolchain.util.TestUtils
import spock.lang.Specification

class ImageStoreSpec extends Specification {

    /** "ABC" in base64, which is enough to write a file with. */
    private static final String CONTENT = 'QUJD'

    private String basePath

    def setup() {
        basePath = "${TestUtils.TEST_OUTPUT_DIR}/${this.getClass().getSimpleName()}/${System.nanoTime()}/"
        new File(basePath).mkdirs()
    }

    def 'an image that already sits in a configured directory is used where it is'() {
        given:
            new File(basePath, 'assets').mkdirs()
            new File(basePath, 'assets/diagram.png').text = 'already here'

        when:
            def stored = new ImageStore(['./assets']).store(basePath, 'diagram.png', 'png', CONTENT)

        then: 'nothing is written, and the path points at the file that was found'
            new File(stored.filePath()).exists()
            new File(stored.filePath()).text == 'already here'
            stored.fileName() == 'diagram.png'
    }

    def 'an image found in the default directory is used as well'() {
        given:
            new File(basePath, 'images').mkdirs()
            new File(basePath, 'images/diagram.png').text = 'already here'

        when:
            def stored = new ImageStore([]).store(basePath, 'diagram.png', 'png', CONTENT)

        then:
            stored.fileName() == 'diagram.png'
            new File(stored.filePath()).exists()
    }

    def 'a directory written as #configured still yields a usable path'() {
        given:
            new File(basePath, 'images').mkdirs()
            new File(basePath, 'images/diagram.png').text = 'already here'

        when:
            def stored = new ImageStore([configured]).store(basePath, 'diagram.png', 'png', CONTENT)

        then: """Concatenation produced .../assetsdiagram.png without a trailing slash and
                 .../images/.diagram.png for docToolchain's own default of 'images/.'. Neither
                 existed, so the deferred upload opened a file that was not there."""
            new File(stored.filePath()).exists()

        where:
            configured << ['./images', './images/', 'images/.', 'images']
    }

    def 'an image that is nowhere on disk is written out, named by its hash'() {
        when:
            def stored = new ImageStore([]).store(basePath, 'inline.png', 'png', CONTENT)

        then: 'the name is the hash, so the same image inlined twice is written once'
            stored.fileName() == "${ContentHash.md5(CONTENT)}.png"
            new File(stored.filePath()).exists()
            new File(stored.filePath()).bytes == 'ABC'.bytes
    }

    def 'writing the same embedded image twice leaves one file'() {
        when:
            def first = new ImageStore([]).store(basePath, 'a.png', 'png', CONTENT)
            def second = new ImageStore([]).store(basePath, 'b.png', 'png', CONTENT)

        then:
            first.filePath() == second.filePath()
            new File(basePath, 'confluence/images').listFiles().length == 1
    }

    def 'a configured directory that does not hold the file is skipped'() {
        given:
            new File(basePath, 'elsewhere').mkdirs()

        when:
            def stored = new ImageStore(['./elsewhere']).store(basePath, 'inline.png', 'png', CONTENT)

        then: 'it falls through to writing the image out'
            stored.fileName().endsWith('.png')
            stored.fileName() != 'inline.png'
    }
}
