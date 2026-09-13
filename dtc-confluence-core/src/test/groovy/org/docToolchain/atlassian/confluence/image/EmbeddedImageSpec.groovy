package org.docToolchain.atlassian.confluence.image

import spock.lang.Specification
import spock.lang.Unroll

class EmbeddedImageSpec extends Specification {

    @Unroll
    def 'a #mime data URI is split into extension, encoding and content'() {
        when:
            def image = EmbeddedImage.parse("data:image/${mime};base64,QUJD")

        then:
            image.fileExtension() == extension
            image.encoding() == 'base64'
            image.content() == 'QUJD'

        where:
            mime      || extension
            'png'     || 'png'
            'jpeg'    || 'jpeg'
            'gif'     || 'gif'
            'svg+xml' || 'svg'
    }

    def 'svg+xml is shortened, because it would not make a usable file extension'() {
        expect:
            EmbeddedImage.parse('data:image/svg+xml;base64,QUJD').fileExtension() == 'svg'
    }
}
