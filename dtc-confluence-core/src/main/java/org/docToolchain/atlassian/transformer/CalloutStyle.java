package org.docToolchain.atlassian.transformer;

import java.util.Locale;
import java.util.Map;

/**
 * How a callout marker is carried into a Confluence code macro.
 *
 * <p>The macro holds plain text: what it shows and what it copies are the same field, so a marker
 * cannot be both visible and absent from the clipboard the way Asciidoctor's HTML manages. Which
 * side of that to give up is the author's decision, not ours - see {@code docs/decisions.adoc}.</p>
 */
public enum CalloutStyle {

    /**
     * The marker becomes a comment in the code. Keeps it beside its line, at the cost of a block
     * that cannot be copied and run. The default, because changing what existing documents publish
     * should be a decision rather than an upgrade.
     */
    COMMENT,

    /**
     * The markers are left out and the macro is given line numbers, so the callout list can refer
     * to them. The code stays copyable character for character.
     */
    LINENUMBERS,

    /**
     * The annotated block is published as it is, followed by a collapsed copy without markers.
     * Nothing is lost, at the cost of every annotated block appearing twice.
     */
    EXPAND;

    private static final Map<String, CalloutStyle> BY_NAME = Map.of(
            "comment", COMMENT,
            "linenumbers", LINENUMBERS,
            "expand", EXPAND);

    /**
     * @param configured the value of {@code confluence.callouts}, or {@code null} where it is unset
     * @return the style it names, or {@link #COMMENT} where nothing usable was configured
     */
    public static CalloutStyle from(Object configured) {
        if (configured == null) {
            return COMMENT;
        }
        String name = String.valueOf(configured).trim().toLowerCase(Locale.ROOT);
        CalloutStyle style = BY_NAME.get(name);
        if (style == null) {
            System.out.println(">>> WARN: confluence.callouts is '" + configured
                    + "', which is none of " + String.join(", ", BY_NAME.keySet())
                    + ". Falling back to comment.");
            return COMMENT;
        }
        return style;
    }
}
