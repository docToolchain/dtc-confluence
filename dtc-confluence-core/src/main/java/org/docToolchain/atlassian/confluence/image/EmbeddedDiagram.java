package org.docToolchain.atlassian.confluence.image;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * The diagram a picture carries inside it, read out of one and written into one.
 *
 * <p>A draw.io export can keep its own source in the picture it renders: a PNG in a {@code tEXt} or
 * {@code zTXt} chunk, an SVG in a {@code content} attribute. Either way it is a whole
 * {@code <mxfile>}, so a picture that carries one is a diagram that happens to look like a
 * picture.</p>
 *
 * <p>Both directions of the round trip live here, and that is the point. The publisher reads a
 * source out of a picture to turn it into a draw.io macro; the export writes one into a picture so
 * that the next publish finds it again. Were these two implementations, the export could write
 * something the publisher does not recognise and the round trip would break in the middle, with
 * each half passing its own tests. One class, and {@code embedIn} followed by {@code of} is the
 * identity.</p>
 *
 * <p>Measured, against files the draw.io CLI and the Confluence app wrote:</p>
 * <ul>
 *   <li>the CLI writes {@code zTXt} under the keyword {@code mxGraphModel}, not {@code mxfile},
 *       although the payload is an {@code mxfile}. Both keywords are read, because draw.io reads
 *       both and the keyword is not the format;</li>
 *   <li>the PNG payload is per-cent encoded as JavaScript's {@code encodeURIComponent} writes it;
 *       the SVG attribute is only HTML-escaped. So the encoding is decided by what the text looks
 *       like after unwrapping, not by the container;</li>
 *   <li>the preview the Confluence app keeps beside a diagram carries no source at all, which is
 *       why an export has to put one there.</li>
 * </ul>
 */
public final class EmbeddedDiagram {

    /** The eight bytes every PNG starts with. */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1A, '\n'};

    /** What draw.io has called the chunk holding a diagram, in either generation. */
    private static final Set<String> DIAGRAM_KEYWORDS = Set.of("mxfile", "mxGraphModel");

    /** What this writes. The payload is an {@code mxfile}, so that is what the keyword says. */
    private static final String KEYWORD = "mxfile";

    /** A chunk is four bytes of length, four of type and four of checksum around its payload. */
    private static final int CHUNK_OVERHEAD = 12;

    /** Where the first chunk starts, the signature being eight bytes. */
    private static final int FIRST_CHUNK = 8;

    private EmbeddedDiagram() {
    }

    /**
     * @param picture the bytes of a file an {@code image::} refers to
     * @return the {@code mxfile} it carries, or empty where it carries none - which is every
     *         ordinary picture, and is not an error
     */
    public static Optional<String> of(byte[] picture) {
        if (picture == null || picture.length == 0) {
            return Optional.empty();
        }
        return isPng(picture) ? inPng(picture) : inSvg(picture);
    }

    /**
     * Puts a diagram into a PNG, so that the picture and its source are one file.
     *
     * <p>Any chunk already carrying a diagram is dropped rather than kept: a preview uploaded from
     * a draw.io export carries the source it was rendered from, and that one is older than the
     * diagram being written here. Two sources in one file would leave which one wins to whoever
     * reads it first.</p>
     *
     * <p>The chunk goes in front of the first {@code IDAT}, because draw.io stops looking for one
     * when the image data starts.</p>
     *
     * @throws IllegalArgumentException where no diagram can be written into this file at all -
     *         it is not a PNG, a chunk of it says it is longer than the file, or it holds no
     *         image data to put the chunk in front of. The caller chose the file, and a picture
     *         answered without the diagram in it is a picture whose source is about to be deleted.
     */
    public static byte[] embedIn(byte[] png, String diagram) {
        if (!isPng(png)) {
            throw new IllegalArgumentException("a diagram can only be embedded in a PNG");
        }
        byte[] chunk = textChunk(encode(diagram.trim()));
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + chunk.length);
        out.write(png, 0, FIRST_CHUNK);
        boolean written = false;
        for (int at = FIRST_CHUNK; at + CHUNK_OVERHEAD <= png.length; ) {
            int length = intAt(png, at);
            if (!fits(length, png, at)) {
                throw new IllegalArgumentException("a chunk of this PNG says it is longer than the"
                        + " file, so the picture is not one a diagram can be written into");
            }
            String type = typeAt(png, at);
            if (!written && ("IDAT".equals(type) || "IEND".equals(type))) {
                out.write(chunk, 0, chunk.length);
                written = true;
            }
            if (!carriesADiagram(type, png, at, length)) {
                out.write(png, at, CHUNK_OVERHEAD + length);
            }
            at += CHUNK_OVERHEAD + length;
        }
        if (!written) {
            // Nowhere to put it: a picture truncated before its image data holds no IDAT and no
            // IEND. Answered as a picture, it would be a preview without the source in it, and
            // the caller - having been told the merge worked - deletes the only other copy.
            throw new IllegalArgumentException("this PNG holds no image data, so there is nothing"
                    + " to put a diagram in front of");
        }
        return out.toByteArray();
    }

    /**
     * How big a PNG is, read off its header.
     *
     * <p>Needed because a draw.io macro is sized by its parameters and not by the picture: the
     * app's rendered container is {@code width:Wpx;height:Hpx}, and a height that does not match
     * the picture's shape would squash it. The ratio is the only thing taken from here; the width
     * is the document's to decide.</p>
     *
     * @return the size in pixels, or empty where this is not a PNG with a readable header
     */
    public static Optional<Size> sizeOfPng(byte[] png) {
        // The first chunk of every PNG is IHDR, whose first eight bytes are width and height.
        int header = FIRST_CHUNK + CHUNK_OVERHEAD - 4;
        if (png == null || !isPng(png) || png.length < header + 8
                || !"IHDR".equals(typeAt(png, FIRST_CHUNK))) {
            return Optional.empty();
        }
        int width = intAt(png, header);
        int height = intAt(png, header + 4);
        return width > 0 && height > 0
                ? Optional.of(new Size(width, height)) : Optional.empty();
    }

    /**
     * @return whether this text is a diagram rather than something else that was embedded
     */
    public static boolean isDiagram(String text) {
        String start = text == null ? "" : text.stripLeading();
        return start.startsWith("<mxfile") || start.startsWith("<mxGraphModel");
    }

    /**
     * @return whether a chunk of this declared length is still inside the file
     *
     *     <p>Subtraction rather than addition: a length of {@code 0x7fffffff} plus the overhead is
     *     a negative number, which passed an addition's bounds check and then indexed at a
     *     negative offset. The loop has already seen that the overhead itself fits, so the right
     *     hand side cannot be negative.</p>
     */
    private static boolean fits(int length, byte[] png, int at) {
        return length >= 0 && length <= png.length - at - CHUNK_OVERHEAD;
    }

    private static boolean isPng(byte[] content) {
        if (content.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int at = 0; at < PNG_SIGNATURE.length; at++) {
            if (content[at] != PNG_SIGNATURE[at]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks the chunks looking for a text chunk whose keyword is draw.io's.
     *
     * <p>Bounded at every step: the file comes from a repository or from Confluence, and a
     * truncated picture must answer "no diagram" rather than throw.</p>
     */
    private static Optional<String> inPng(byte[] png) {
        for (int at = FIRST_CHUNK; at + CHUNK_OVERHEAD <= png.length; ) {
            int length = intAt(png, at);
            if (!fits(length, png, at)) {
                return Optional.empty();
            }
            String type = typeAt(png, at);
            Optional<String> keyword = diagramKeywordAt(type, png, at, length);
            if (keyword.isPresent()) {
                return payloadOf(type, png, at, length, keyword.get().length())
                        .map(EmbeddedDiagram::decode)
                        .filter(EmbeddedDiagram::isDiagram);
            }
            if ("IEND".equals(type)) {
                return Optional.empty();
            }
            at += CHUNK_OVERHEAD + length;
        }
        return Optional.empty();
    }

    /**
     * @return the keyword of this chunk where it is a text chunk naming a diagram, empty otherwise
     */
    private static Optional<String> diagramKeywordAt(String type, byte[] png, int at, int length) {
        if (!"tEXt".equals(type) && !"zTXt".equals(type)) {
            return Optional.empty();
        }
        int from = at + FIRST_CHUNK;
        for (int cursor = from; cursor < from + length; cursor++) {
            if (png[cursor] == 0) {
                String keyword = new String(png, from, cursor - from, StandardCharsets.ISO_8859_1);
                return DIAGRAM_KEYWORDS.contains(keyword) ? Optional.of(keyword) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean carriesADiagram(String type, byte[] png, int at, int length) {
        return diagramKeywordAt(type, png, at, length).isPresent();
    }

    /**
     * @return the text behind the keyword, inflated where the chunk is the compressed kind
     */
    private static Optional<String> payloadOf(String type, byte[] png, int at, int length,
                                              int keywordLength) {
        // Past the keyword and the zero that ends it, and for zTXt past the compression method.
        int skipped = keywordLength + 1 + ("zTXt".equals(type) ? 1 : 0);
        int from = at + FIRST_CHUNK + skipped;
        int remaining = length - skipped;
        if (remaining <= 0) {
            return Optional.empty();
        }
        if ("tEXt".equals(type)) {
            return Optional.of(new String(png, from, remaining, StandardCharsets.ISO_8859_1));
        }
        return inflate(png, from, remaining);
    }

    private static Optional<String> inflate(byte[] png, int from, int length) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(png, from, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(length * 4);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int written = inflater.inflate(buffer);
                if (written == 0) {
                    break;
                }
                out.write(buffer, 0, written);
            }
            return Optional.of(out.toString(StandardCharsets.UTF_8));
        } catch (DataFormatException notCompressed) {
            // A text chunk that says it is compressed and is not. Nothing to read here.
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads the {@code content} attribute of the root element, rather than the first one in the
     * file: an SVG may hold further elements carrying a {@code content} of their own, and only the
     * root's is draw.io's.
     */
    private static Optional<String> inSvg(byte[] picture) {
        String text = new String(picture, StandardCharsets.UTF_8);
        if (!text.contains("<svg")) {
            return Optional.empty();
        }
        Element root = Jsoup.parse(text, "", Parser.xmlParser()).selectFirst("svg");
        if (root == null) {
            return Optional.empty();
        }
        // jsoup has resolved the HTML escaping of the attribute already.
        String content = root.attr("content");
        return content.isEmpty()
                ? Optional.empty()
                : Optional.of(decode(content)).filter(EmbeddedDiagram::isDiagram);
    }

    /**
     * @return the text as the diagram it stands for, per-cent decoded where it is encoded
     */
    private static String decode(String payload) {
        if (isDiagram(payload)) {
            return payload;
        }
        try {
            // A plus means itself here, as it does in a path: escaped first, because URLDecoder
            // decodes a form field, where it stands for a space.
            return URLDecoder.decode(payload.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // A per-cent sign that is not an escape: whatever this chunk holds, it is not a
            // diagram, and the caller decides that by looking at the answer.
            return payload;
        }
    }

    /**
     * JavaScript's {@code encodeURIComponent}, which is what draw.io decodes with.
     *
     * <p>{@link URLEncoder} writes a form field: a space becomes a plus, and five characters that
     * {@code encodeURIComponent} leaves alone are escaped. Both are corrected here, so that what
     * draw.io reads back is what went in.</p>
     */
    private static String encode(String diagram) {
        return URLEncoder.encode(diagram, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    /**
     * A {@code zTXt} chunk: keyword, zero, compression method, deflated text.
     *
     * <p>Compressed rather than plain, because a per-cent encoded diagram is some three times its
     * own size and deflates back to well below it - measured on the showcase diagram, 14 268
     * characters of XML became a 2 163 byte chunk.</p>
     */
    private static byte[] textChunk(String encoded) {
        byte[] payload = payload(encoded);
        byte[] type = "zTXt".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(payload.length + CHUNK_OVERHEAD);
        writeInt(chunk, payload.length);
        chunk.write(type, 0, type.length);
        chunk.write(payload, 0, payload.length);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload);
        writeInt(chunk, (int) crc.getValue());
        return chunk.toByteArray();
    }

    private static byte[] payload(String encoded) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] keyword = KEYWORD.getBytes(StandardCharsets.ISO_8859_1);
        payload.write(keyword, 0, keyword.length);
        payload.write(0);
        // 0 is the only compression method PNG defines, and it is deflate.
        payload.write(0);
        byte[] deflated = deflate(encoded.getBytes(StandardCharsets.ISO_8859_1));
        payload.write(deflated, 0, deflated.length);
        return payload.toByteArray();
    }

    private static byte[] deflate(byte[] text) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(text);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(text.length / 2);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static int intAt(byte[] content, int at) {
        return ((content[at] & 0xFF) << 24) | ((content[at + 1] & 0xFF) << 16)
                | ((content[at + 2] & 0xFF) << 8) | (content[at + 3] & 0xFF);
    }

    private static String typeAt(byte[] content, int at) {
        return new String(content, at + 4, 4, StandardCharsets.US_ASCII);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** A picture's size in pixels. */
    public record Size(int width, int height) {

        /**
         * @return how tall this picture is when drawn that wide, keeping its shape
         */
        public int heightAt(int displayedWidth) {
            return Math.max(1, Math.round((float) displayedWidth * height / width));
        }
    }
}
