package org.docToolchain.atlassian.confluence.image

import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * States what a picture carrying a draw.io diagram is, read from the shapes real tools write.
 *
 * The fixtures are built here rather than taken from a file, and built by hand rather than by the
 * class under test: a reader tested against its own writer would pass however wrong both were.
 * The shapes are the measured ones - the draw.io CLI writes a zTXt chunk under the keyword
 * "mxGraphModel" whose payload is per-cent encoded, and an SVG carries the same XML in a "content"
 * attribute that is only HTML-escaped.
 */
class EmbeddedDiagramSpec extends Specification {

    private static final byte[] SIGNATURE = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]

    private static final String DIAGRAM =
        '<mxfile host="drawio-plugin"><diagram id="view-core" name="Core">xyz</diagram></mxfile>'

    /** JavaScript's encodeURIComponent, which is what draw.io writes the payload with. */
    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
            .replace('+', '%20').replace('%21', '!').replace('%27', "'")
            .replace('%28', '(').replace('%29', ')').replace('%7E', '~')
    }

    private static byte[] chunk(String type, byte[] payload) {
        def out = new ByteArrayOutputStream()
        def length = payload.length
        out.write([(length >>> 24) & 0xFF, (length >>> 16) & 0xFF,
                   (length >>> 8) & 0xFF, length & 0xFF] as byte[])
        def typeBytes = type.getBytes(StandardCharsets.US_ASCII)
        out.write(typeBytes)
        out.write(payload)
        def crc = new CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        def value = (int) crc.value
        out.write([(value >>> 24) & 0xFF, (value >>> 16) & 0xFF,
                   (value >>> 8) & 0xFF, value & 0xFF] as byte[])
        return out.toByteArray()
    }

    private static byte[] textPayload(String keyword, String text) {
        def out = new ByteArrayOutputStream()
        out.write(keyword.getBytes(StandardCharsets.ISO_8859_1))
        out.write(0)
        out.write(text.getBytes(StandardCharsets.ISO_8859_1))
        return out.toByteArray()
    }

    private static byte[] deflatedPayload(String keyword, String text) {
        def out = new ByteArrayOutputStream()
        out.write(keyword.getBytes(StandardCharsets.ISO_8859_1))
        out.write(0)
        out.write(0)
        def deflater = new Deflater()
        deflater.setInput(text.getBytes(StandardCharsets.ISO_8859_1))
        deflater.finish()
        def buffer = new byte[8192]
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    /** An IHDR saying how big the picture is, which is the only part of it read here. */
    private static byte[] header(int width, int height) {
        def out = new ByteArrayOutputStream()
        [width, height].each {
            out.write([(it >>> 24) & 0xFF, (it >>> 16) & 0xFF, (it >>> 8) & 0xFF, it & 0xFF] as byte[])
        }
        out.write(new byte[5])
        return out.toByteArray()
    }

    /** A PNG of the chunks given, in that order, around the image data. */
    private static byte[] png(byte[]... extra) {
        return sizedPng(944, 694, extra)
    }

    private static byte[] sizedPng(int width, int height, byte[]... extra) {
        def out = new ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(chunk('IHDR', header(width, height)))
        extra.each { out.write(it) }
        out.write(chunk('IDAT', 'pixels'.getBytes(StandardCharsets.ISO_8859_1)))
        out.write(chunk('IEND', new byte[0]))
        return out.toByteArray()
    }

    private static byte[] svg(String contentAttribute) {
        def escaped = contentAttribute.replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;')
        return ('<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" ' +
            'content="' + escaped + '"><g/></svg>').getBytes(StandardCharsets.UTF_8)
    }

    def 'a PNG written by the draw.io command line carries its diagram in a zTXt chunk'() {
        given: 'measured: the keyword is mxGraphModel, although the payload is an mxfile'
            def picture = png(chunk('zTXt', deflatedPayload('mxGraphModel', encoded(DIAGRAM))))

        expect:
            EmbeddedDiagram.of(picture).get() == DIAGRAM
    }

    def 'a tEXt chunk is read as well, uncompressed'() {
        expect:
            EmbeddedDiagram.of(png(chunk('tEXt', textPayload('mxfile', encoded(DIAGRAM)))))
                .get() == DIAGRAM
    }

    def 'an SVG carries the same diagram in a content attribute, HTML-escaped and not encoded'() {
        given: '''measured against a file the draw.io command line wrote: unescaping the attribute
                  is enough, and per-cent decoding it afterwards changes nothing. So the encoding
                  is decided by what the text looks like, not by the container it came in.'''
            def picture = svg(DIAGRAM)

        expect:
            EmbeddedDiagram.of(picture).get() == DIAGRAM
    }

    def 'an ordinary picture carries no diagram, and that is not an error'() {
        given: 'the whole publishing rule rests on this answer'
            def plain = png()
            def withOtherText = png(chunk('tEXt', textPayload('Software', 'something else')))

        expect:
            EmbeddedDiagram.of(plain).isEmpty()
            EmbeddedDiagram.of(withOtherText).isEmpty()
            EmbeddedDiagram.of(svg('not a diagram at all')).isEmpty()
            EmbeddedDiagram.of('<svg xmlns="x"><g/></svg>'.bytes).isEmpty()
            EmbeddedDiagram.of(new byte[0]).isEmpty()
            EmbeddedDiagram.of(null).isEmpty()
    }

    def 'what is embedded can be read back out, which is the whole round trip'() {
        given: '''The export writes the source into a picture and the publisher reads it out
                  again. Were these two implementations, each could pass its own tests while the
                  round trip broke between them.'''
            def picture = png()

        when:
            def carrying = EmbeddedDiagram.embedIn(picture, DIAGRAM)

        then:
            EmbeddedDiagram.of(carrying).get() == DIAGRAM

        and: 'and it is still the picture it was, byte for byte around the chunk'
            new String(carrying, StandardCharsets.ISO_8859_1).contains('pixels')
            carrying[0..7] as byte[] == SIGNATURE
    }

    def 'the characters encodeURIComponent leaves alone survive the round trip'() {
        given: '''URLEncoder writes a form field: a space becomes a plus and five characters are
                  escaped that encodeURIComponent leaves alone. Uncorrected, a label reading
                  "a + b" came back as "a   b".'''
            def awkward = '<mxfile><diagram id="x">a + b, 50% of (c) ~ d! e\'s "f" &amp; g' +
                '</diagram></mxfile>'

        expect:
            EmbeddedDiagram.of(EmbeddedDiagram.embedIn(png(), awkward)).get() == awkward
    }

    def 'a diagram already in the picture is replaced, not kept beside the new one'() {
        given: '''A preview uploaded from a draw.io export carries the source it was rendered
                  from, which is older than the diagram being written into it. Two sources in one
                  file leave it to whoever reads it first which one wins.'''
            def older = '<mxfile><diagram id="old">before</diagram></mxfile>'
            def carrying = png(chunk('zTXt', deflatedPayload('mxGraphModel', encoded(older))))

        when:
            def merged = EmbeddedDiagram.embedIn(carrying, DIAGRAM)

        then:
            EmbeddedDiagram.of(merged).get() == DIAGRAM

        and: 'the old one is gone rather than further along the file'
            !new String(merged, StandardCharsets.ISO_8859_1).contains('mxGraphModel')
    }

    def 'the chunk goes in front of the image data, where draw.io stops looking'() {
        when:
            def merged = EmbeddedDiagram.embedIn(png(), DIAGRAM)

        then:
            def text = new String(merged, StandardCharsets.ISO_8859_1)
            text.indexOf('zTXt') < text.indexOf('IDAT')
    }

    def 'a truncated picture answers that it carries no diagram, rather than throwing'() {
        given: 'the file comes from a repository or from a server, so it may be anything'
            def whole = png(chunk('zTXt', deflatedPayload('mxGraphModel', encoded(DIAGRAM))))

        expect:
            EmbeddedDiagram.of(whole[0..<(whole.length - 40)] as byte[]).isEmpty()
            EmbeddedDiagram.of(whole[0..12] as byte[]).isEmpty()
    }

    def 'only a PNG can be given a diagram, and asking refuses rather than writes rubbish'() {
        when: 'an SVG would need its root attribute rewritten, which is a different job'
            EmbeddedDiagram.embedIn(svg(DIAGRAM), DIAGRAM)

        then:
            def refused = thrown(IllegalArgumentException)
            refused.message.contains('PNG')
    }

    def 'a diagram is told apart from anything else that may be in a text chunk'() {
        expect:
            EmbeddedDiagram.isDiagram('<mxfile host="x"/>')
            EmbeddedDiagram.isDiagram('\n  <mxGraphModel/>')
            !EmbeddedDiagram.isDiagram('<svg/>')
            !EmbeddedDiagram.isDiagram('')
            !EmbeddedDiagram.isDiagram(null)
    }

    def 'how big a PNG is, because a macro is sized by its parameters and not by its picture'() {
        given: '''Measured: the rendered container is width:Wpx;height:Hpx, and the diagram's own
                  size is nowhere in the file the document names. The picture's shape is the only
                  honest number there is.'''

        expect:
            EmbeddedDiagram.sizeOfPng(sizedPng(944, 694)).get() == new EmbeddedDiagram.Size(944, 694)

        and: 'and how tall it is when drawn as wide as the document asks'
            EmbeddedDiagram.sizeOfPng(sizedPng(944, 694)).get().heightAt(700) == 515
            new EmbeddedDiagram.Size(1, 1).heightAt(700) == 700

        and: 'never nothing: a container of no height would collapse the picture'
            new EmbeddedDiagram.Size(1000, 1).heightAt(10) == 1
    }

    def 'anything that is not a PNG with a header has no size to read'() {
        expect:
            EmbeddedDiagram.sizeOfPng(svg(DIAGRAM)).isEmpty()
            EmbeddedDiagram.sizeOfPng(SIGNATURE).isEmpty()
            EmbeddedDiagram.sizeOfPng(null).isEmpty()
            EmbeddedDiagram.sizeOfPng(sizedPng(0, 694)).isEmpty()
    }
}
