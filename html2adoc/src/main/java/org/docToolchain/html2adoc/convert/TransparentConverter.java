/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.Set;

import org.docToolchain.html2adoc.Html2Adoc;
import org.docToolchain.html2adoc.context.IContext;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Elements that become nothing: the children are written and the element itself is dropped.
 *
 * <p>{@code <div>} and {@code <span>} group HTML for layout, and {@code <thead>} and {@code <tbody>}
 * group rows of a table whose shape AsciiDoc states on the table instead. None of them has an
 * AsciiDoc equivalent, and none of them separates two words.</p>
 *
 * <p>That last part is why the drop is deliberate rather than left to the unknown-element fallback
 * of {@code Html2Adoc}: the fallback owes the next text a separating space, which is right for
 * markup that stood <em>between</em> two words and wrong for a wrapper that merely encloses them —
 * {@code dtc-<span>confluence</span>} is one word.</p>
 *
 * <p>No {@code class} is read. In the corpus every class on a {@code <div>} is layout
 * ({@code sectionbody}, {@code content}, {@code code-wrapper}). The two shapes whose class does
 * carry meaning, {@code div.imageblock} and {@code div.footnote}, need a converter that knows about
 * images and footnotes and are not handled here.</p>
 */
public class TransparentConverter extends AbstractConverter {

    private static final Set<String> TAG_NAMES = Set.of("div", "span", "thead", "tbody");

    @Override
    public boolean canConvert(IContext context, Node node) {
        if (node instanceof Element) {
            return TAG_NAMES.contains(((Element) node).tagName());
        }
        return false;
    }

    @Override
    public IContext convert(IContext context, Node node, StringBuilder sb) {
        // The position the children end at is the position after the wrapper: that is what being
        // transparent means.
        return Html2Adoc.convert(context, sb, node);
    }
}
