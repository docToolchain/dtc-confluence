/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * {@code <pre>} contributes no markup of its own and makes the text inside it verbatim.
 *
 * <p>The text is written unescaped, because that is what verbatim means and because an escape
 * inside an AsciiDoc literal or listing block is printed rather than consumed - AsciiDoc
 * substitutes nothing there, so a backslash put in front of {@code ${HOME}} to stop an attribute
 * reference reaches the page as part of the code sample.</p>
 *
 * <p>A {@code <pre>} holding a single {@code <code>} is the HTML for a listing block, and is
 * written as one: the delimiters, and a {@code [source, lang]} line where the {@code <code>}
 * carries a {@code class="language-lang"}. That is the shape a code macro arrives in and the
 * shape pandoc reads, so both converters write the same block out of the same HTML.</p>
 *
 * <p>A {@code <pre>} without that {@code <code>} gets no delimiter line, because then the HTML
 * does not say which AsciiDoc block it is - it may be a literal block or a listing - and whoever
 * produced it knows and writes the delimiters around it.</p>
 *
 * <p>Whitespace is collapsed as it is in any other text. Keeping it is the other half of what
 * {@code <pre>} means, and a larger change than this one: it decides where the lines of the block
 * break, and the one producer whose output reaches this converter marks its own line breaks
 * instead.</p>
 */
public class PreformattedConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "pre".equals(((Element) node).tagName());
        }
        return false;
    }

    /** What a {@code <code>} names its language with, as HTML and as pandoc read it. */
    private static final String LANGUAGE = "language-";

    /** The listing delimiter. Four is the minimum AsciiDoc accepts. */
    private static final String DELIMITER = "----";

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        IContext verbatim = ContextBuilder.build(context).withVerbatim(true).create();
        Element sample = sampleIn((Element) node);
        if (sample == null) {
            Html2Adoc.convert(verbatim, sb, node);
            // Verbatim ends with the element: what follows it is prose again.
            return context;
        }
        startBlock(sb);
        String language = languageOf(sample);
        if (!language.isEmpty()) {
            sb.append("[source, ").append(language).append("]\n");
        }
        // The text rather than a conversion of it: a <code> on its own is inline monospace, and
        // walking into it here would wrap the sample in backticks inside its own listing block.
        sb.append(DELIMITER).append('\n')
                .append(sample.wholeText().strip())
                .append('\n').append(DELIMITER).append('\n');
        return context;
    }

    /**
     * @return the one {@code <code>} this {@code <pre>} holds, or {@code null} where it holds
     *         anything else - text of its own, or more than one element
     */
    private static Element sampleIn(Element pre) {
        return pre.children().size() == 1
                && "code".equals(pre.child(0).tagName())
                && isBlankOrNoBreakSpace(pre.ownText())
                ? pre.child(0) : null;
    }

    private static String languageOf(Element sample) {
        for (String name : sample.classNames()) {
            if (name.startsWith(LANGUAGE)) {
                return name.substring(LANGUAGE.length());
            }
        }
        return "";
    }
}
