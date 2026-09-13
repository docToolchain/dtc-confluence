package org.docToolchain.atlassian.confluence.page;

import org.docToolchain.util.ContentHash;

/**
 * Wraps a converted page body with the navigation Confluence needs and a hash of the content.
 *
 * <p>Confluence has no automatic table of contents, so one is inserted as a macro, and a list of
 * child pages is appended. The hash at the end is what later tells an unchanged page from a
 * changed one, so that republishing an untouched document writes nothing.</p>
 */
public class PageDecorator {

    private static final String DEFAULT_TABLE_OF_CONTENTS =
            "<p><ac:structured-macro ac:name=\"toc\"/></p>";

    private static final String DEFAULT_TABLE_OF_CHILDREN =
            "<p><ac:structured-macro ac:name=\"children\">"
                    + "<ac:parameter ac:name=\"sort\">creation</ac:parameter>"
                    + "</ac:structured-macro></p>";

    private final boolean tableOfContentsDisabled;
    private final String extraPageContent;
    private final String tableOfContents;
    private final String tableOfChildren;

    /**
     * @param tableOfContentsDisabled leave out both the contents and the children macro
     * @param extraPageContent        markup to put above every page body, may be null
     * @param tableOfContents         replacement for the contents macro, may be null
     * @param tableOfChildren         replacement for the children macro, may be null
     */
    public PageDecorator(boolean tableOfContentsDisabled, String extraPageContent,
                         String tableOfContents, String tableOfChildren) {
        this.tableOfContentsDisabled = tableOfContentsDisabled;
        this.extraPageContent = extraPageContent;
        this.tableOfContents = tableOfContents;
        this.tableOfChildren = tableOfChildren;
    }

    public String decorate(String body) {
        StringBuilder content = new StringBuilder();
        if (!tableOfContentsDisabled) {
            content.append(orDefault(tableOfContents, DEFAULT_TABLE_OF_CONTENTS));
        }
        content.append(orDefault(extraPageContent, ""));
        content.append(body);
        if (!tableOfContentsDisabled) {
            content.append(orDefault(tableOfChildren, DEFAULT_TABLE_OF_CHILDREN));
        }
        // The hash covers the body alone, so changing the decoration does not look like a content
        // change and republishing an untouched document still writes nothing.
        content.append("<ac:placeholder>hash: #").append(ContentHash.md5(body)).append("#</ac:placeholder>");
        return content.toString();
    }

    private static String orDefault(String configured, String fallback) {
        return configured == null || configured.isEmpty() ? fallback : configured;
    }
}
