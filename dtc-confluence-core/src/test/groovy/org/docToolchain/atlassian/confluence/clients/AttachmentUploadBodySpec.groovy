package org.docToolchain.atlassian.confluence.clients

import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * An attachment is bound by its exact name: the page body names it, the draw.io app looks for it.
 * A name the upload spells differently from the body is a page pointing at nothing.
 */
class AttachmentUploadBodySpec extends Specification {

    def 'an attachment name is sent as the name it is'() {
        expect:
            dispositionOf(name) == expected

        where: 'a plain name, one with a space, and two the export never sanitises'
            name            || expected
            'diagram.png'   || 'filename="diagram.png"'
            'my image.png'  || 'filename="my image.png"'
            'Übersicht.png' || 'filename="Übersicht.png"'
            'Plan ü.png'    || 'filename="Plan ü.png"'
    }

    /**
     * Pins the combination rather than the intent: EXTENDED would answer the second case with
     * {@code my%20image.png} and the third with {@code %C3%9Cbersicht.png}, and STRICT would
     * write the umlaut as a single byte.
     */
    private static String dispositionOf(String fileName) {
        def body = new ByteArrayOutputStream()
        ConfluenceClient.multipart(new ByteArrayInputStream('a picture'.bytes), fileName,
                'automatically uploaded', '0123456789abcdef0123456789abcdef', 'probeBoundary').writeTo(body)
        new String(body.toByteArray(), StandardCharsets.UTF_8)
                .readLines()
                .find { it.contains('filename') }
                .trim()
                .replaceAll(/.*;\s*/, '')
    }
}
