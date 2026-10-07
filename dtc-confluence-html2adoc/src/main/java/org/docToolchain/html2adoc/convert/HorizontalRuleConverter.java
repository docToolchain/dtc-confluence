/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * {@code <hr>} becomes {@code '''}, the AsciiDoc thematic break.
 *
 * <p>AsciiDoc reads {@code '''} as a break only where it stands alone with a blank line above it;
 * anywhere else it is paragraph text. The output written so far decides what is missing, rather
 * than the context, because a converter is free to leave the line open without recording it — the
 * inline {@code image:} of a block image does exactly that, and an {@code <hr>} follows one in the
 * corpus.</p>
 */
public class HorizontalRuleConverter extends AbstractConverter {

    private static final String THEMATIC_BREAK = "'''";

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "hr".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        startBlock(sb);
        sb.append(THEMATIC_BREAK).append("\n\n");
        return ContextBuilder.build(context).withLineStarted(false).withSpaceNeeded(false).create();
    }
}
