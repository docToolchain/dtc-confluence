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
import org.jsoup.nodes.Element;
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

    /**
     * Headings that fix the base level: the ones in the page's own flow.
     *
     * <p>A heading inside a table cell, a list item or a {@code <pre>} is not a section of the
     * document - and a {@code <h1>} in a cell would otherwise push every real heading of the page
     * a level deeper, which is the skipped level this shift exists to avoid.</p>
     */
    private static final String STRUCTURAL_HEADINGS =
            ":is(h1, h2, h3, h4, h5, h6):not(:is(table, pre, li) *)";

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
     * Converts {@code node}, writing its shallowest heading at {@code topLevel} and every deeper
     * one as far below that as it stands below the shallowest.
     *
     * <p>A caller that writes a heading of its own above this fragment has to say so, or the
     * fragment outranks it: HTML numbers headings from the top of the document, so {@code <h1>}
     * becomes a level-0 section - a document title - and a page written under
     * {@code "== Title"} then has two. Shifting rather than mapping each level separately is what
     * keeps a page that starts at {@code <h2>} from skipping a level, which asciidoctor reports as
     * a section out of sequence.</p>
     *
     * @param topLevel the AsciiDoc level for the shallowest heading: 0 writes {@code =}, 1
     *                 {@code ==}, and so on
     */
    public static String convert(Node node, int topLevel) {
        IContext context = ContextBuilder.build()
                .withHeadingShift(topLevel + 1 - shallowestHeading(node))
                .create();
        StringBuilder sb = new StringBuilder();
        convert(context, sb, node);
        return sb.toString();
    }

    /**
     * @return the {@code N} of the shallowest {@code <hN>} in {@code node}, or 1 where it holds no
     *         heading at all - the shift is then whatever the caller asked for and changes nothing
     */
    private static int shallowestHeading(Node node) {
        if (!(node instanceof Element)) {
            return 1;
        }
        // Not seeded with 6: an <h6> is a legal shallowest heading, and a sentinel inside the
        // domain cannot be told apart from it. A page whose headings are all <h6> was shifted as
        // though it had none, which put its one section at level 5 under a level-1 title.
        int shallowest = Integer.MAX_VALUE;
        for (Element heading : ((Element) node).select(STRUCTURAL_HEADINGS)) {
            shallowest = Math.min(shallowest, Integer.parseInt(heading.tagName().substring(1)));
        }
        return shallowest == Integer.MAX_VALUE ? 1 : shallowest;
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
