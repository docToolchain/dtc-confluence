/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Drops {@code <br>} and leaves a space behind.
 *
 * <p>A line break inside a paragraph carries no meaning in AsciiDoc, where the renderer rewraps;
 * keeping it would turn soft HTML layout into a hard break. It still separates two words, hence the
 * space.</p>
 */
public class BrConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "br".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        return ContextBuilder.build(context).withSpaceNeeded(true).create();
    }
}
