/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.Locale;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * {@code <a href>} becomes one of five AsciiDoc forms, depending on where it points.
 *
 * <p>A link to localhost is not a link at all once the document is read somewhere else, so it is
 * written as monospaced text. A relative link to an HTML file assumes that file is being converted
 * too and rewrites its extension to {@code .adoc}. Anything else keeps its target verbatim in a
 * {@code link:} macro - an absolute URL, and a relative path to something that is not a converted
 * document, which is what an attachment is.</p>
 *
 * <p>Every {@code <a>} with a target is converted, and the fragment is optional in each of the two
 * forms that can carry one. The fragment used to be required for a link to another file, which
 * dropped the target of every link without one and left the words behind as plain text; a link to a
 * sibling page and a link to an attachment are both written without a fragment.</p>
 */
public class LinkConverter extends AbstractConverter {

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            Element e = (Element) node;
            // An <a> with no target is an anchor rather than a link: HeaderConverter writes the
            // ones that anchor a heading, and elsewhere only the text it wraps is content.
            return "a".equals(e.tagName()) && !e.attr("href").isEmpty();
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        IContext spaceContext = ContextBuilder.build(context).withSpaceNeeded(true).create();
        addSpaceIfNeeded(spaceContext, sb);
        Element e = (Element) node;
        IContext newContext = ContextBuilder.build(context).withSpaceNeeded(false).create();
        if (isLocalhostLink(e)) {
            sb.append("`");
            Html2Adoc.convert(newContext, sb, node);
            sb.append("`");
        } else if (isInternalLinkSamePage(e)) {
            sb.append("<<");
            sb.append(e.attr("href").substring(1));
            sb.append(",");
            Html2Adoc.convert(newContext, sb, node);
            sb.append(">>");
        } else if (isInternalLinkOtherPage(e)) {
            String href = e.attr("href");
            int fragment = href.indexOf('#');
            String path = fragment < 0 ? href : href.substring(0, fragment);
            sb.append("<<");
            sb.append(path, 0, path.lastIndexOf('.'));
            sb.append(".adoc#");
            // An empty fragment is the AsciiDoc way of naming the top of the other document.
            sb.append(fragment < 0 ? "" : href.substring(fragment + 1));
            sb.append(",");
            Html2Adoc.convert(newContext, sb, node);
            sb.append(">>");
        } else {
            sb.append("link:");
            sb.append(e.attr("href"));
            sb.append("[");
            Html2Adoc.convert(newContext, sb, node);
            sb.append("]");
        }
        return context;
    }

    private boolean isLocalhostLink(Element e) {
        return e.attr("href").startsWith("http://localhost");
    }

    private boolean isExternalLink(Element e) {
        return e.attr("href").startsWith("http");
    }

    private boolean isInternalLinkSamePage(Element e) {
        return e.attr("href").startsWith("#");
    }

    /**
     * Whether the link names an HTML file of this conversion, which will therefore exist as
     * AsciiDoc beside the document being written. An absolute URL is excluded however it ends: a
     * page on another server is not being converted, and its extension says nothing about that.
     */
    private boolean isInternalLinkOtherPage(Element e) {
        if (isExternalLink(e)) {
            return false;
        }
        String href = e.attr("href");
        int fragment = href.indexOf('#');
        String path = (fragment < 0 ? href : href.substring(0, fragment)).toLowerCase(Locale.ROOT);
        return path.endsWith(".html") || path.endsWith(".htm");
    }
}
