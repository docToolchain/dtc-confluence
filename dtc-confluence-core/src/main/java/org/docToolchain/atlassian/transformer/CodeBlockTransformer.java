package org.docToolchain.atlassian.transformer;

import java.util.Map;
import java.util.Set;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Turns AsciiDoctor's {@code <pre><code>} blocks into Confluence code macros.
 */
class CodeBlockTransformer {

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
            stripHighlightingMarkup(code);
            code.before("<ac:parameter ac:name=\"language\">" + language + "</ac:parameter>");
            code.parent() // the pre
                    .wrap("<ac:structured-macro ac:name=\"code\"></ac:structured-macro>")
                    .unwrap();
            code.wrap("<ac:plain-text-body>"
                            + ConfluenceTags.CDATA_PLACEHOLDER_START + ConfluenceTags.CDATA_PLACEHOLDER_END
                            + "</ac:plain-text-body>")
                    .unwrap();
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
     * AsciiDoctor marks up syntax highlighting that the Confluence macro does its own way.
     * Bold text becomes a comment marker, which is how docToolchain has always rendered callouts.
     */
    private static void stripHighlightingMarkup(Element code) {
        code.select("span[class]").forEach(Element::unwrap);
        code.select("i[class]").forEach(Element::unwrap);
        for (Element bold : code.select("b")) {
            bold.before(" // ");
            bold.unwrap();
        }
    }
}
