/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Node;

/**
 * Converts one kind of HTML node to AsciiDoc.
 *
 * <p>One implementation per HTML construct, so that an unsupported construct is a missing class
 * rather than a missing branch. {@code Html2Adoc} asks each in turn and the first that says yes
 * takes the node.</p>
 */
public interface IConverter {

    /**
     * Whether this converter handles {@code node} at this position.
     *
     * @param context the position in the output, for converters whose answer depends on it
     * @param node the node about to be converted
     * @return {@code true} if {@link #convert} may be called with this node
     */
    boolean canConvert(IContext context, Node node);

    /**
     * Appends the AsciiDoc for {@code node} to {@code sb}.
     *
     * @param context the position in the output before this node
     * @param node the node to convert, which the converter may descend into
     * @param sb the output written so far, appended to in place
     * @return the position after this node, which is {@code context} unless the node moved it
     */
    IContext convert(IContext context, Node node, StringBuilder sb);
}
