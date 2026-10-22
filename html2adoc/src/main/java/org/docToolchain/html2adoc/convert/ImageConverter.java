/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * {@code <img>} becomes a block image ({@code image::}) or an inline one ({@code image:}).
 *
 * <p>HTML does not distinguish the two; the position does. An image that is the only content of its
 * paragraph, or sits directly in the body, was meant as a figure. One standing between words, or
 * inside a table cell where a block image would break the cell, is inline.</p>
 */
public class ImageConverter extends AbstractConverter {

    /** The alignments AsciiDoc builds a role out of. */
    private static final Set<String> ALIGNMENTS = Set.of("left", "center", "right");

    /** Any run of blank space, which a value cannot carry: a newline in it ends the macro. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** What AsciiDoc resolves when the page is rendered, as {@code AdocText} spells it. */
    private static final Pattern ATTRIBUTE_REFERENCE =
            Pattern.compile("\\{(?:\\w[\\w-]*|(?:set|counter2?):[^}]+)}");


    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "img".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        Element element = (Element) node;
        Element parent = element.parent();
        boolean imageBloc = parent != null && isNotInsideATable(parent) && opensItsOwnLine(element, sb);
        if (imageBloc) {
            sb.append("\n");
            sb.append("image::");
        } else {
            sb.append(" image:");
        }
        sb.append(element.attr("src"));
        sb.append(attributesOf(element, imageBloc));
        if (imageBloc) {
            sb.append("\n");
            if ("body".equals(parent.tagName())) {
                // No paragraph converter will follow to close the block, so do it here.
                sb.append("\n");
            }
        } else {
            sb.append(" ");
        }
        return context;
    }

    /**
     * Whether the image begins a line of the output, which is what decides the two forms:
     * {@code image::} is a block image and has to stand at the start of a line, {@code image:}
     * is inline and follows the words it stands among.
     *
     * <p>A Confluence image block arrives as {@code div.imageblock > div.content > img} among the
     * other blocks of a page. {@link TransparentConverter} drops both divs, so the image stands
     * where they stood - at the start of a line, after whatever block precedes it. Written as an
     * inline image it is indented by the space that separates one from preceding words, and an
     * indented line is a literal block: the page then prints the markup instead of showing the
     * image.</p>
     *
     * <p>A paragraph is asked rather than the output, because a paragraph states it: an image
     * alone in a {@code <p>} is a block image, and one sharing the paragraph with anything else -
     * a second image, a word - is not, however the line happens to have broken.</p>
     */
    private boolean opensItsOwnLine(Element image, StringBuilder sb) {
        Element element = image;
        Element parent = element.parent();
        while (parent != null
                && TransparentConverter.TAG_NAMES.contains(parent.tagName())
                && holdsOnly(parent, element)) {
            element = parent;
            parent = element.parent();
        }
        if (parent == null) {
            return false;
        }
        if ("p".equals(parent.tagName())) {
            return holdsOnly(parent, element);
        }
        return "body".equals(parent.tagName())
                || sb.length() == 0
                || AdocText.lastCharacterOf(sb) == '\n';
    }

    /** Whether {@code parent} holds {@code only} and, apart from it, nothing but empty space. */
    private boolean holdsOnly(Element parent, Element only) {
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode) {
                if (!isBlankOrNoBreakSpace(((TextNode) child).text())) {
                    return false;
                }
            } else if (child != only) {
                return false;
            }
        }
        return true;
    }

    /**
     * The attribute list, named rather than positional.
     *
     * <p>Upstream wrote {@code []} and read only the {@code src}, so the size an author set and
     * the alignment they chose were dropped: a 64-pixel icon came out at its full size. Named
     * because the positional order of an image's attributes is alt, width, height, and a width
     * written without an alt before it would be read as the alt text.</p>
     *
     * <p>No alt text is invented where the HTML has none. pandoc derives one from the file name,
     * which is how {@code alt="1++_++"} reaches an exported page.</p>
     */
    private String attributesOf(Element image, boolean imageBloc) {
        StringJoiner attributes = new StringJoiner(",", "[", "]");
        for (String name : List.of("alt", "width", "height", "align")) {
            String value = value(image, name, imageBloc);
            if (!value.isEmpty()) {
                attributes.add(name + "=\"" + value + "\"");
            }
        }
        return attributes.toString();
    }

    /** @return the attribute's value, ready to stand in a quoted attribute list, or empty */
    private String value(Element image, String name, boolean imageBloc) {
        String value = WHITESPACE.matcher(image.attr(name)).replaceAll(" ").trim();
        if ("align".equals(name)) {
            // Lower case and only the three AsciiDoc builds a role from: it turns align=center
            // into the role text-center, and HTML lets an author write ALIGN="CENTER", which
            // would become text-CENTER - a role no stylesheet has a rule for. An inline image
            // has no alignment at all, so the attribute is left off rather than written dead.
            String alignment = value.toLowerCase(Locale.ROOT);
            return imageBloc && ALIGNMENTS.contains(alignment) ? alignment : "";
        }
        return escaped(value, imageBloc);
    }

    /**
     * Escapes what AsciiDoc would otherwise read as something else.
     *
     * <p>A quotation mark ends the value and an attribute reference is resolved when the page is
     * rendered - a counter in an alt text is even incremented. Both have an escape, {@code \"}
     * and {@code \{}, and both are honoured. A {@code ]} ends an *inline* macro's attribute list
     * and needs escaping there; in a block macro the list is the whole line, where an escaped
     * {@code ]} would print its backslash, so it is left alone. A backslash of the author's own
     * needs nothing - written raw it comes out raw, while doubling it prints both - except at the
     * very end, where it would escape the quotation mark that closes the value.</p>
     */
    private String escaped(String value, boolean imageBloc) {
        String escaped = ATTRIBUTE_REFERENCE.matcher(value).replaceAll("\\\\$0")
                .replace("\"", "\\\"");
        if (!imageBloc) {
            escaped = escaped.replace("]", "\\]");
        }
        return escaped.endsWith("\\") ? escaped.substring(0, escaped.length() - 1) : escaped;
    }

    private boolean isNotInsideATable(Element parent) {
        Element e = parent;
        while (e != null) {
            if ("table".equals(e.tagName())) {
                return false;
            }
            e = e.parent();
        }
        return true;
    }

}
