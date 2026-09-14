package org.docToolchain.atlassian.transformer;

import java.util.Map;
import java.util.Set;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * Turns AsciiDoctor's {@code <pre><code>} blocks into Confluence code macros.
 */
class CodeBlockTransformer {

    /**
     * What Asciidoctor renders a callout as: an empty {@code i} carrying the number as an
     * attribute, followed by a sibling {@code b} carrying it as the text to show. CSS hides the
     * {@code b} and generates the marker from the attribute, which is why a browser copies clean
     * code and a Confluence macro cannot.
     */
    private static final String CALLOUT_SELECTOR = "i.conum[data-value]";

    /** Languages the Confluence code macro highlights; anything else renders an error. */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "actionscript3", "applescript", "bash", "c#", "cpp", "css", "coldfusion", "delphi",
            "diff", "erl", "groovy", "xml", "java", "jfx", "js", "php", "perl", "text",
            "powershell", "py", "ruby", "sql", "sass", "scala", "vb", "yml");

    private static final Map<String, String> LANGUAGE_MAPPING = Map.of(
            "json", "yml", // acceptable workaround
            "shell", "bash",
            "yaml", "yml");

    /** Confluence defaults to Java when no language is given, so say plain text explicitly. */
    private static final String DEFAULT_LANGUAGE = "text";

    private final CalloutStyle calloutStyle;

    CodeBlockTransformer() {
        this(CalloutStyle.COMMENT);
    }

    CodeBlockTransformer(CalloutStyle calloutStyle) {
        this.calloutStyle = calloutStyle;
    }

    /**
     * @return the code blocks that were transformed, so callers can tell how many were found
     */
    Elements transformCodeBlock(Element body) {
        Elements codeBlocks = body.select("pre > code");
        for (Element code : codeBlocks) {
            String language = languageOf(code);
            if ("xml".equals(language)) {
                escapeNestedCdata(code);
            }
            boolean hadCallouts = !code.select(CALLOUT_SELECTOR).isEmpty();
            String plainCopy = hadCallouts && calloutStyle == CalloutStyle.EXPAND
                    ? withoutCallouts(code)
                    : null;
            stripHighlightingMarkup(code, language);
            Element pre = code.parent();
            code.before("<ac:parameter ac:name=\"language\">" + language + "</ac:parameter>");
            if (hadCallouts && calloutStyle == CalloutStyle.LINENUMBERS) {
                code.before("<ac:parameter ac:name=\"linenumbers\">true</ac:parameter>");
            }
            pre.wrap("<ac:structured-macro ac:name=\"code\"></ac:structured-macro>");
            Element macro = pre.parent();
            pre.unwrap();
            code.wrap("<ac:plain-text-body>"
                            + ConfluenceTags.CDATA_PLACEHOLDER_START + ConfluenceTags.CDATA_PLACEHOLDER_END
                            + "</ac:plain-text-body>")
                    .unwrap();
            if (plainCopy != null) {
                appendPlainCopy(macro, language, plainCopy);
            }
        }
        return codeBlocks;
    }

    private static String languageOf(Element code) {
        String language = code.attr("data-lang");
        if (language.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        language = LANGUAGE_MAPPING.getOrDefault(language, language);
        return SUPPORTED_LANGUAGES.contains(language) ? language : DEFAULT_LANGUAGE;
    }

    /**
     * A CDATA section inside an XML sample would terminate the CDATA section the macro itself
     * sits in, so the inner terminator is split. See docToolchain issue #1265.
     */
    private static void escapeNestedCdata(Element code) {
        String xmlDocument = code.wholeOwnText();
        if (xmlDocument.contains("<![CDATA[") && xmlDocument.contains("]]>")) {
            code.text(xmlDocument.replace("]]>", "]]]]><![CDATA[>"));
        }
    }

    /**
     * AsciiDoctor marks up syntax highlighting that the Confluence macro does its own way, and
     * wraps a callout marker in {@code i.conum > b}. What becomes of that marker is the configured
     * style's business.
     */
    private void stripHighlightingMarkup(Element code, String language) {
        code.select("span[class]").forEach(Element::unwrap);
        // Only linenumbers takes the markers out of the block itself; expand keeps them here and
        // adds a copy without them underneath.
        if (calloutStyle != CalloutStyle.LINENUMBERS) {
            warnAboutLineContinuations(code, language);
            String opening = CommentSyntax.opening(language);
            String closing = CommentSyntax.closing(language);
            for (Element marker : code.select(CALLOUT_SELECTOR)) {
                // Asciidoctor usually leaves a space before the marker, but not after the "\#"
                // spelling that keeps a shell continuation copyable - so add one only if needed.
                String before = textBefore(marker);
                String separator = before.isEmpty() || Character.isWhitespace(
                        before.charAt(before.length() - 1)) ? "" : " ";
                // A text node, not markup: this is code, and "<!---" as markup is a comment
                // declaration that Jsoup swallows. As text it is escaped on the way out and
                // unescaped again inside the CDATA section it lands in.
                marker.before(new TextNode(
                        separator + opening + " " + markerTextOf(marker) + closing));
                removeCallout(marker);
            }
        } else {
            code.select(CALLOUT_SELECTOR).forEach(CodeBlockTransformer::removeCallout);
        }
        // Whatever highlighting markup is left, including a b outside a callout.
        code.select("i[class]").forEach(Element::unwrap);
        code.select("b").forEach(Element::unwrap);
    }

    /**
     * @return what the marker shows, from the sibling {@code b} that carries it, or the attribute
     *         where a renderer left no such sibling
     */
    private static String markerTextOf(Element marker) {
        Element shown = marker.nextElementSibling();
        return shown != null && "b".equals(shown.tagName())
                ? shown.text() : "(" + marker.attr("data-value") + ")";
    }

    /**
     * Removes both halves of a callout: the empty marker and the sibling holding its text.
     */
    private static void removeCallout(Element marker) {
        Element shown = marker.nextElementSibling();
        if (shown != null && "b".equals(shown.tagName())) {
            shown.remove();
        }
        marker.remove();
    }

    /**
     * @return the block's text with the callout markers taken out, for the collapsed copy
     */
    private static String withoutCallouts(Element code) {
        Element copy = code.clone();
        copy.select(CALLOUT_SELECTOR).forEach(CodeBlockTransformer::removeCallout);
        copy.select("span[class]").forEach(Element::unwrap);
        copy.select("i[class]").forEach(Element::unwrap);
        copy.select("b").forEach(Element::unwrap);
        return copy.wholeText();
    }

    /**
     * Adds a collapsed copy of the block without its markers, so the code can still be copied.
     */
    private static void appendPlainCopy(Element macro, String language, String plainCopy) {
        Element expand = macro.after("<ac:structured-macro ac:name=\"expand\">"
                        + "<ac:parameter ac:name=\"title\">the same code, without the callout markers"
                        + "</ac:parameter><ac:rich-text-body>"
                        + "<ac:structured-macro ac:name=\"code\">"
                        + "<ac:parameter ac:name=\"language\">" + language + "</ac:parameter>"
                        + "<ac:plain-text-body>"
                        + ConfluenceTags.CDATA_PLACEHOLDER_START
                        + ConfluenceTags.CDATA_PLACEHOLDER_END
                        + "</ac:plain-text-body></ac:structured-macro>"
                        + "</ac:rich-text-body></ac:structured-macro>")
                .nextElementSibling();
        if (expand == null) {
            return;
        }
        Element placeholder = expand.selectFirst("cdata-placeholder");
        if (placeholder != null) {
            // As text, so Jsoup escapes whatever the code contains. The placeholder stays an
            // element, and HtmlTransformer unescapes between the two markers on the way out.
            placeholder.text(plainCopy);
        }
    }

    /**
     * A shell continues a line with a trailing backslash, and that backslash has to be the last
     * character on the line - so a comment after it breaks the command rather than being ignored.
     * Nothing can be done about it here; the author is the one who can decide.
     */
    private static void warnAboutLineContinuations(Element code, String language) {
        if (!CommentSyntax.brokenByLineContinuation(language)) {
            return;
        }
        for (Element marker : code.select(CALLOUT_SELECTOR)) {
            String before = textBefore(marker);
            if (before.stripTrailing().endsWith("\\")) {
                System.out.println(">>> WARN: a callout follows a line continuation in a "
                        + language + " block. As a comment it breaks the command; consider "
                        + "confluence.callouts = 'linenumbers' or 'expand'.");
                return;
            }
        }
    }

    private static String textBefore(Element marker) {
        StringBuilder text = new StringBuilder();
        for (Node node : marker.parent().childNodes()) {
            if (node == marker) {
                break;
            }
            text.append(node instanceof TextNode textNode
                    ? textNode.getWholeText() : ((Element) node).wholeText());
        }
        int lastBreak = text.lastIndexOf("\n");
        return lastBreak < 0 ? text.toString() : text.substring(lastBreak + 1);
    }
}
