package org.docToolchain.atlassian.constants;

/**
 * Markers used while building Confluence storage format.
 *
 * <p>Confluence requires some content to sit inside CDATA sections, which Jsoup would escape if
 * they were present while the DOM is still being manipulated. The transformers therefore insert
 * these placeholder elements and {@code HtmlTransformer} swaps them for real CDATA markers once
 * the DOM has been serialised to a string.</p>
 */
public final class ConfluenceTags {

    public static final String CDATA_PLACEHOLDER_START = "<cdata-placeholder>";
    public static final String CDATA_PLACEHOLDER_END = "</cdata-placeholder>";

    private ConfluenceTags() {
    }
}
