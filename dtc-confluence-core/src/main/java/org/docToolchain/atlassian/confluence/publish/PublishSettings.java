package org.docToolchain.atlassian.confluence.publish;

import java.util.Map;

import groovy.util.ConfigObject;
import org.docToolchain.atlassian.confluence.page.PageTreeBuilder;

/**
 * What one entry of {@code confluence.input} decides, with the global settings folded in.
 *
 * <p>Every value may be given per input or once for all of them, and "not given" arrives in two
 * shapes. A section built by property assignment answers a missing key with an <em>empty
 * ConfigObject</em>; one written as {@code confluence = [:]} with a with block is a plain map,
 * where a missing key is simply {@code null}. Reading one shape and not the other is how a
 * deprecation guard came to reject the absence of the option it was meant to reject, and how a
 * label somebody deliberately emptied came back as the default. Both are handled in one place
 * here.</p>
 */
public final class PublishSettings {

    /** Where nothing says otherwise, a page per top-level section. */
    private static final int DEFAULT_SUBPAGES_FOR_SECTIONS = 1;

    private final String spaceKey;
    private final int subpagesForSections;
    private final String pagePrefix;
    private final String pageSuffix;
    private final String footnoteLabel;
    private final String ancestorId;
    private final String ancestorName;

    private PublishSettings(String spaceKey, int subpagesForSections, String pagePrefix,
                            String pageSuffix, String footnoteLabel, String ancestorId,
                            String ancestorName) {
        this.spaceKey = spaceKey;
        this.subpagesForSections = subpagesForSections;
        this.pagePrefix = pagePrefix;
        this.pageSuffix = pageSuffix;
        this.footnoteLabel = footnoteLabel;
        this.ancestorId = ancestorId;
        this.ancestorName = ancestorName;
    }

    /**
     * @param input      one entry of {@code confluence.input}
     * @param confluence the {@code confluence} section, for the values it does not override
     * @throws IllegalStateException naming the migration, where a removed option is still set
     */
    public static PublishSettings of(Map<?, ?> input, Map<?, ?> confluence) {
        refuseRemovedOptions(input, confluence);
        return new PublishSettings(
                text(either(input, confluence, "spaceKey")),
                subpagesFrom(either(input, confluence, "subpagesForSections")),
                text(either(input, confluence, "pagePrefix")),
                text(either(input, confluence, "pageSuffix")),
                footnoteLabelFrom(value(confluence, "footnoteLabel")),
                text(either(input, confluence, "ancestorId")),
                text(value(input, "ancestorName")));
    }

    public String spaceKey() {
        return spaceKey;
    }

    public int subpagesForSections() {
        return subpagesForSections;
    }

    public String pagePrefix() {
        return pagePrefix;
    }

    public String pageSuffix() {
        return pageSuffix;
    }

    public String footnoteLabel() {
        return footnoteLabel;
    }

    /** @return the configured ancestor, empty where the page is to be created at the space root */
    public String ancestorId() {
        return ancestorId;
    }

    /** @return the ancestor named rather than numbered, which the caller has to look up */
    public String ancestorName() {
        return ancestorName;
    }

    /**
     * The options that were removed rather than renamed, so that a configuration still carrying
     * one is stopped with the migration rather than published with a meaning it lost.
     */
    private static void refuseRemovedOptions(Map<?, ?> input, Map<?, ?> confluence) {
        if (isSet(either(input, confluence, "createSubpages"))
                || isSet(either(input, confluence, "allInOnePage"))) {
            System.out.println("ERROR:");
            System.out.println("Deprecated configuration, migrate as follows:");
            System.out.println("allInOnePage = true -> subpagesForSections = 0");
            System.out.println("allInOnePage = false && createSubpages = false -> subpagesForSections = 1");
            System.out.println("allInOnePage = false && createSubpages = true -> subpagesForSections = 2");
            throw new IllegalStateException("config problem");
        }
        if (isSet(either(input, confluence, "preambleTitle"))) {
            System.out.println("ERROR:");
            System.out.println("Deprecated configuration, use first level heading in document "
                    + "instead of preambleTitle configuration");
            throw new IllegalStateException("config problem");
        }
    }

    private static int subpagesFrom(Object configured) {
        if (configured instanceof Number number) {
            return number.intValue();
        }
        if (isSet(configured)) {
            try {
                return Integer.parseInt(String.valueOf(configured).trim());
            } catch (NumberFormatException notANumber) {
                System.out.println(">>> WARN: confluence.subpagesForSections is '" + configured
                        + "', which is not a number. Using " + DEFAULT_SUBPAGES_FOR_SECTIONS + ".");
            }
        }
        return DEFAULT_SUBPAGES_FOR_SECTIONS;
    }

    /**
     * @return the label to write in front of a footnote definition - the default where nobody
     *         configured one, and an empty string where somebody emptied it on purpose
     */
    private static String footnoteLabelFrom(Object configured) {
        return isSet(configured)
                ? String.valueOf(configured) : PageTreeBuilder.DEFAULT_FOOTNOTE_LABEL;
    }

    /**
     * @return the per-input value where it is set, the global one otherwise
     */
    private static Object either(Map<?, ?> input, Map<?, ?> confluence, String key) {
        Object perInput = value(input, key);
        return isSet(perInput) ? perInput : value(confluence, key);
    }

    private static Object value(Map<?, ?> from, String key) {
        return from == null ? null : from.get(key);
    }

    /**
     * @return whether the author actually wrote this, in either shape absence takes
     */
    private static boolean isSet(Object value) {
        return value != null && !(value instanceof ConfigObject);
    }

    private static String text(Object value) {
        return isSet(value) ? String.valueOf(value) : "";
    }
}
