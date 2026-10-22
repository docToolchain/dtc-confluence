/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Writes text, escaped so that it stays text, and decides on both sides of it whether a space
 * belongs there.
 *
 * <p>The decision is made from the first and last character: a word character wants to be separated
 * from what precedes it, while punctuation such as {@code :} or {@code !} must stay attached. That
 * is what keeps {@code <p>This is<strong>important</strong>!</p>} from losing the space before the
 * bold run and gaining one before the exclamation mark. Both characters are read before escaping,
 * because a backslash the escaping put in front of the text says nothing about the spacing.</p>
 *
 * <p>This is the only converter that writes text a person typed; everything else writes markup. So
 * it is also the only caller of {@link AdocText} - and the only place where escaping can be turned
 * off, which {@link PreformattedConverter} does for text that AsciiDoc will read verbatim.</p>
 */
public class TextNodeConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        return node instanceof TextNode;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        String text = ((TextNode) node).text();
        if (text != null) {
            String trimmedText = text.trim();
            if (trimmedText.isEmpty() && startsWithSpace(text)) {
                // Nothing to write, but the node is the space itself and has to say so.
                // Confluence wraps almost everything in a <span>, so "</span> <strong>" is the
                // usual shape and the space between the two is a text node of its own. Passed
                // over without a word, the comma of "Lorem,</span> <strong>ipsum" was written
                // against the span that followed it.
                return ContextBuilder.build(context).withSpaceNeeded(true).create();
            }
            if (!trimmedText.isEmpty()) {
                // The node's own whitespace, and the character class as well. The whitespace is
                // what HTML says, and jsoup has already collapsed runs of it to one space. The
                // character class is not a worse answer to the same question but an answer to
                // another one: an unknown element is dropped by Html2Adoc without its text being
                // separated from what follows, and the class is what keeps those two words apart.
                // Asked by the class alone, a text ending in "," or ";" offered no space, so the
                // space after a comma before an inline span was lost - which changes the words on
                // the page.
                if (startsWithSpace(text) || trimmedText.substring(0, 1).matches("[a-zA-Z0-9/()]")) {
                    addSpaceIfNeeded(context, sb);
                }
                sb.append(context.isVerbatim()
                        ? trimmedText
                        : AdocText.escape(trimmedText, AdocText.lastCharacterOf(sb),
                                context.getCellSeparators()));
                boolean spaceNeeded = endsWithSpace(text)
                        || trimmedText.substring(trimmedText.length() - 1).matches("[a-zA-Z0-9.:/()]");
                return ContextBuilder.build(context).withSpaceNeeded(spaceNeeded).withLineStarted(true).create();
            }
        }
        return context;
    }

    private static boolean startsWithSpace(String text) {
        return !text.isEmpty() && isSpace(text.charAt(0));
    }

    private static boolean endsWithSpace(String text) {
        return !text.isEmpty() && isSpace(text.charAt(text.length() - 1));
    }

    /**
     * Exactly what {@link String#trim()} removes, which is what makes this question answerable:
     * the text written out is the trimmed text, so a character counts as a separator only where
     * trimming took it away.
     *
     * <p>{@link Character#isWhitespace} is the wrong test here. It answers yes for an en quad and
     * an ideographic space, which {@code trim} leaves in place and jsoup does not collapse - the
     * character would then be written out as part of the text *and* answered with a space of its
     * own, so {@code <p>Lorem,&#x2000;<strong>ipsum</strong></p>} came out with two separators
     * between the comma and the span. A no-break space needs no mention: {@code isWhitespace}
     * says no to it already, and so does this.</p>
     */
    private static boolean isSpace(char character) {
        return character <= ' ';
    }
}
