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
        for (Element code : body.select(LISTING_SELECTOR)) {
            // Resolved per listing. The Groovy computed one URL for the whole body and took the
            // last it found, so a page with two API documents pointed both macros at the same
            // one, and an inline listing beside a url: one was turned into a fetch of that URL.
            String includeUrl = OPEN_API.equals(macro) ? includeUrlOf(code) : null;
            // The listing is read before the DOM is rearranged, and put back as raw text
            // afterwards, so Jsoup does not escape it a second time.
            String rawYaml = code.wholeText();
            code.parent().wrap(macroFor(macro)).unwrap();

            if (OPEN_API.equals(macro) && includeUrl != null) {
                code.before("<ac:parameter ac:name=\"url\">" + includeUrl + "</ac:parameter>");
                // The listing element stays, as it did in the Groovy, so the macro receives the
                // document as well as the URL. Whether the plugin ignores it or chokes on it is
                // untested: the plugin is not installed on any instance available here. Removing
                // it is a change to published output that should be verified first.
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
     * @return the document URL carried in a {@code url:} class on the listing block this code
     *         belongs to, or null if it carries none
     */
    private static String includeUrlOf(Element code) {
        Element block = code.closest(".listingblock.openapi");
        if (block == null) {
            return null;
        }
        String includeUrl = null;
        for (String className : block.className().split(" ")) {
            if (className.startsWith("url")) {
                includeUrl = className.replace("url:", "");
            }
        }
        return includeUrl;
    }
}
