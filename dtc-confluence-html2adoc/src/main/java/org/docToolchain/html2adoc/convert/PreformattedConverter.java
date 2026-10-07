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
 * <p>No delimiter line is written, because HTML does not say which AsciiDoc block this is: a
 * {@code <pre>} may be a literal block, a listing block, or source in a named language. Whoever
 * produced the {@code <pre>} knows that and writes the delimiters around it.</p>
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

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        IContext newContext = ContextBuilder.build(context).withVerbatim(true).create();
        Html2Adoc.convert(newContext, sb, node);
        // Verbatim ends with the element: what follows it is prose again.
        return context;
    }
}
