/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/** {@code <li>} becomes {@code *} or {@code .}, taken from the list recorded in the context. */
public class ListItemConverter extends AbstractConverter {

    /** An attribute that resolves to nothing, which is how AsciiDoc writes an empty line's text. */
    private static final String EMPTY = "{empty}";


    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return "li".equals(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        if (context.getListType() == null) {
            throw new IllegalStateException("A list item outside any list");
        }
        // As many markers as the list is deep: AsciiDoc reads "**" as an item of a list inside
        // the one "*" belongs to. A depth of zero cannot occur - getListType() is null outside any
        // list, and that is refused above.
        int depth = Math.max(context.getListDepth(), 1);
        switch (context.getListType()) {
            case UL:
                sb.append("*".repeat(depth));
                break;
            case OL:
                sb.append(".".repeat(depth));
                break;
            default:
                throw new IllegalStateException("Unexpected list type: " + context.getListType());
        }
        sb.append(" ");
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).withLineStarted(true).create();
        int text = sb.length();
        Html2Adoc.convert(newContext, sb, node);
        // A marker with nothing after it is not a list item to AsciiDoc but the start of a
        // paragraph, and an <li> that holds only a nested list writes exactly that. The list then
        // begins at the nested item instead, and what follows it is read against that depth: the
        // items of the outer list became children of the nested ones. {empty} is a line's worth of
        // nothing, which makes the marker an item that owns what is indented under it.
        if (sb.length() == text || sb.charAt(text) == '\n') {
            sb.insert(text, EMPTY);
        }
        sb.append("\n");
        return context;
    }
}
