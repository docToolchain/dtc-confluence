package org.docToolchain.atlassian.transformer;

import java.util.Map;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Turns AsciiDoctor admonition blocks into the Confluence macros that correspond to them.
 */
public class AdmonitionTransformer {

    /**
     * AsciiDoctor knows five admonitions, Confluence four macros, so two of them share one.
     */
    private static final Map<String, String> MACRO_BY_ADMONITION = Map.of(
            "note", "info",
            "warning", "warning",
            "important", "warning",
            "caution", "note",
            "tip", "tip");

    public void transformAdmonitions(Element body) {
        MACRO_BY_ADMONITION.forEach((admonition, macro) -> {
            for (Element block : body.select(".admonitionblock." + admonition)) {
                transformBlock(block, macro);
            }
        });
    }

    static void transformBlock(Element block, String macroName) {
        Element content = block.select(".content").first();
        Elements title = content.select(".title");
        // The title parameter is always emitted, empty or not. Confluence accepts an empty one,
        // and the published pages have always carried it.
        String titleParameter = "<ac:parameter ac:name=\"title\">" + title.text() + "</ac:parameter>";
        title.remove();

        block.after("<ac:structured-macro ac:name=\"" + macroName + "\">"
                + titleParameter
                + "<ac:rich-text-body>" + content + "</ac:rich-text-body>"
                + "</ac:structured-macro>");
        block.remove();
    }
}
