package org.docToolchain.atlassian.transformer;

import org.docToolchain.atlassian.constants.ConfluenceTags;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/**
 * Hands an OpenAPI listing to whichever Confluence macro is installed.
 *
 * <p>Three different plugins render OpenAPI, and which one to use is a per-site decision, so the
 * macro name comes from the configuration. Without that setting the listing stays an ordinary code
 * block.</p>
 */
public class OpenApiTransformer {

    private static final String CONFLUENCE_OPEN_API = "confluence-open-api";
    private static final String SWAGGER_OPEN_API = "swagger-open-api";
    private static final String OPEN_API = "open-api";

    /** The macro ids are fixed per plugin; Confluence rejects a macro without one. */
    private static final String CONFLUENCE_MACRO_ID = "1dfde21b-6111-4535-928a-470fa8ae3e7d";
    private static final String SWAGGER_MACRO_ID = "f9deda8a-1375-4488-8ca5-3e10e2e4ee70";
    private static final String OPEN_API_MACRO_ID = "4302c9d8-fca4-4f14-99a9-9885128870fa";

    private static final String LISTING_SELECTOR = "div.openapi pre > code";

    private final String macro;

    /**
     * @param useOpenapiMacro the configured value, which may be a macro name, {@code true} for the
     *                        Confluence plugin, or absent
     */
    public OpenApiTransformer(Object useOpenapiMacro) {
        this.macro = macroNameOf(useOpenapiMacro);
    }

    private static String macroNameOf(Object configured) {
        if (Boolean.TRUE.equals(configured) || CONFLUENCE_OPEN_API.equals(configured)) {
            return CONFLUENCE_OPEN_API;
        }
        if (SWAGGER_OPEN_API.equals(configured) || OPEN_API.equals(configured)) {
            return String.valueOf(configured);
        }
        return null;
    }

    public void transformOpenApi(Element body) {
        if (macro == null) {
            return;
        }
        String includeUrl = OPEN_API.equals(macro) ? includeUrlOf(body) : null;
        for (Element code : body.select(LISTING_SELECTOR)) {
            // The listing is read before the DOM is rearranged, and put back as raw text
            // afterwards, so Jsoup does not escape it a second time.
            String rawYaml = code.wholeText();
            code.parent().wrap(macroFor(macro)).unwrap();

            if (OPEN_API.equals(macro) && includeUrl != null) {
                // The macro fetches the document itself, so the listing is not sent along.
                code.before("<ac:parameter ac:name=\"url\">" + includeUrl + "</ac:parameter>");
                continue;
            }
            if (OPEN_API.equals(macro)) {
                code.before("<ac:parameter ac:name=\"showDownloadButton\">true</ac:parameter>");
            }
            code.wrap("<ac:plain-text-body>"
                            + ConfluenceTags.CDATA_PLACEHOLDER_START + ConfluenceTags.CDATA_PLACEHOLDER_END
                            + "</ac:plain-text-body>")
                    .replaceWith(new TextNode(rawYaml));
        }
    }

    private static String macroFor(String macroName) {
        String macroId = switch (macroName) {
            case CONFLUENCE_OPEN_API -> CONFLUENCE_MACRO_ID;
            case SWAGGER_OPEN_API -> SWAGGER_MACRO_ID;
            default -> OPEN_API_MACRO_ID;
        };
        String layout = OPEN_API.equals(macroName) ? " data-layout=\"default\"" : "";
        return "<ac:structured-macro ac:name=\"" + macroName + "\" ac:schema-version=\"1\""
                + layout + " ac:macro-id=\"" + macroId + "\"></ac:structured-macro>";
    }

    /**
     * @return the document URL carried in a {@code url:} class on the listing block, or null
     */
    private static String includeUrlOf(Element body) {
        String includeUrl = null;
        for (Element block : body.select("div .listingblock.openapi")) {
            for (String className : block.className().split(" ")) {
                if (className.startsWith("url")) {
                    includeUrl = className.replace("url:", "");
                }
            }
        }
        return includeUrl;
    }
}
