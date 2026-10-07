/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc;

import java.util.List;

import org.docToolchain.html2adoc.context.ContextBuilder;
import org.docToolchain.html2adoc.context.IContext;
import org.docToolchain.html2adoc.convert.BrConverter;
import org.docToolchain.html2adoc.convert.EmphasisConverter;
import org.docToolchain.html2adoc.convert.HeaderConverter;
import org.docToolchain.html2adoc.convert.HorizontalRuleConverter;
import org.docToolchain.html2adoc.convert.IConverter;
import org.docToolchain.html2adoc.convert.ImageConverter;
import org.docToolchain.html2adoc.convert.LinkConverter;
import org.docToolchain.html2adoc.convert.ListConverter;
import org.docToolchain.html2adoc.convert.ListItemConverter;
import org.docToolchain.html2adoc.convert.ListingConverter;
import org.docToolchain.html2adoc.convert.MonospaceConverter;
import org.docToolchain.html2adoc.convert.ParagraphConverter;
import org.docToolchain.html2adoc.convert.PreformattedConverter;
import org.docToolchain.html2adoc.convert.StrongConverter;
import org.docToolchain.html2adoc.convert.SubscriptConverter;
import org.docToolchain.html2adoc.convert.SuperscriptConverter;
import org.docToolchain.html2adoc.convert.TableConverter;
import org.docToolchain.html2adoc.convert.TableTdConverter;
import org.docToolchain.html2adoc.convert.TableTrConverter;
import org.docToolchain.html2adoc.convert.TextNodeConverter;
import org.docToolchain.html2adoc.convert.TransparentConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;

/**
 * Converts HTML to AsciiDoc.
 *
 * <p>The HTML tree is walked once, and every node is offered to each converter in turn until one
 * takes it; a node nothing claims is skipped but still descended into, so unknown markup disappears
 * and its text survives. Order matters: {@link ListingConverter} must see a table before
 * {@link TableConverter} does, or a code listing would come out as a table.</p>
 */
public final class Html2Adoc {

    private static final List<IConverter> CONVERTERS = List.of(
            new ListingConverter(),
            new PreformattedConverter(),
            new HeaderConverter(),
            new ParagraphConverter(),
            new EmphasisConverter(),
            new MonospaceConverter(),
            new StrongConverter(),
            new SubscriptConverter(),
            new SuperscriptConverter(),
            new LinkConverter(),
            new ListConverter(),
            new ListItemConverter(),
            new ImageConverter(),
            new TableConverter(),
            new TableTrConverter(),
            new TableTdConverter(),
            new TransparentConverter(),
            new TextNodeConverter(),
            new BrConverter(),
            new HorizontalRuleConverter());

    private Html2Adoc() {
    }

    /** Converts an HTML document, or a fragment, to AsciiDoc. */
    public static String convert(String html) {
        Document document = Jsoup.parse(html);
        return convert(document.body());
    }

    /** Converts the children of {@code node} to AsciiDoc. */
    public static String convert(Node node) {
        IContext context = ContextBuilder.build().create();
        StringBuilder sb = new StringBuilder();
        convert(context, sb, node);
        return sb.toString();
    }

    /**
     * Appends the AsciiDoc for the children of {@code rootNode} to {@code sb}.
     *
     * <p>Public because the converters call back into it to convert what is inside the node they
     * took.</p>
     *
     * @return the position after the last child, which the caller needs to keep converting
     */
    public static IContext convert(IContext context, StringBuilder sb, Node rootNode) {
        Node node = firstChild(rootNode);
        while (node != null) {
            IConverter converter = findConverter(context, node);
            if (converter != null) {
                context = converter.convert(context, node, sb);
            } else if (firstChild(node) != null) {
                // An element nobody handles: write nothing for it, but keep its content. It stood
                // between its neighbours in the HTML, so it separates words.
                IContext newContext = ContextBuilder.build(context).withSpaceNeeded(true).create();
                context = convert(newContext, sb, node);
            }
            node = node.nextSibling();
        }
        return context;
    }

    private static Node firstChild(Node node) {
        List<Node> childNodes = node.childNodes();
        if (!childNodes.isEmpty()) {
            return childNodes.get(0);
        }
        return null;
    }

    private static IConverter findConverter(IContext context, Node node) {
        for (IConverter converter : CONVERTERS) {
            if (converter.canConvert(context, node)) {
                return converter;
            }
        }
        return null;
    }
}
