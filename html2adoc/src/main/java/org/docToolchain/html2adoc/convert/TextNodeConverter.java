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
            if (!trimmedText.isEmpty()) {
                if (trimmedText.substring(0, 1).matches("[a-zA-Z0-9/()]")) {
                    addSpaceIfNeeded(context, sb);
                }
                sb.append(context.isVerbatim()
                        ? trimmedText
                        : AdocText.escape(trimmedText, AdocText.lastCharacterOf(sb),
                                context.getCellSeparators()));
                boolean spaceNeeded = trimmedText.substring(trimmedText.length() - 1).matches("[a-zA-Z0-9.:/()]");
                return ContextBuilder.build(context).withSpaceNeeded(spaceNeeded).withLineStarted(true).create();
            }
        }
        return context;
    }
}
