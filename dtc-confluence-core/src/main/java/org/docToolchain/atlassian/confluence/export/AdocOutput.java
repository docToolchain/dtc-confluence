package org.docToolchain.atlassian.confluence.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the placeholders back that the translation left for after pandoc.
 *
 * <p>Pandoc writes AsciiDoc but escapes the punctuation AsciiDoc is built from: a literal bracket
 * becomes {@code ++[++}, an underscore {@code ++_++}. Anything the export needs to reach the
 * document as AsciiDoc rather than as text - an admonition, a block anchor, a column specifier -
 * therefore travels through pandoc as a placeholder and is spelled out here.</p>
 *
 * <p>The placeholders are separated by hyphens, never by underscores, for the same reason.</p>
 */
final class AdocOutput {

    private static final Pattern ADMONITION = Pattern.compile(
            "\\s*(?:%%ADMON-TITLE%%([\\s\\S]*?)%%ADMON-TITLE-END%%\\s*)?%%ADMON-BEGIN-(\\w+)%%\\s*");
    private static final Pattern COLLAPSIBLE = Pattern.compile(
            "\\s*(?:%%EXPAND-TITLE%%([\\s\\S]*?)%%EXPAND-TITLE-END%%\\s*)?%%EXPAND-BEGIN%%\\s*");
    private static final Pattern ANCHOR = Pattern.compile("\\s*%%ANCHOR%%([^%]+)%%ANCHOR-END%%\\s*");
    private static final Pattern ROW_HEADER_TABLE = Pattern.compile(
            "%%TABLE-ROWHEADER-(\\d+)%%\\s*\\n+\\s*(?:\\[[^\\]]*\\]\\s*\\n)?\\|===");
    /**
     * A verbatim block: a delimiter line, everything up to the next line of the same delimiter,
     * and that line. Hyphens for a listing block and dots for a literal one - pandoc writes the
     * latter for a sample whose language Confluence did not record. Reluctant, so two blocks are
     * two matches rather than one spanning both.
     */
    private static final Pattern VERBATIM_BLOCK = Pattern.compile(
            "(?m)^[ \\t]*(-{4,}|\\.{4,})[ \\t]*$.*?^[ \\t]*\\1[ \\t]*$", Pattern.DOTALL);

    /** Around a masked listing: NUL cannot occur in a Confluence page and matches no pattern here. */
    private static final String MASK_PREFIX = "\u0000listing";
    private static final String MASK_SUFFIX = "\u0000";

    private static final Pattern STATUS = Pattern.compile(
            "%%STATUS-BEGIN-(\\w+)%%([\\s\\S]*?)%%STATUS-END%%");

    private AdocOutput() {
    }

    /**
     * @param adoc what pandoc wrote
     * @return the same document with every placeholder spelled out as AsciiDoc
     */
    static String substitute(String adoc) {
        // The listing blocks are taken out of the way first. AsciiDoc substitutes nothing inside
        // them, so neither may this: "image: nginx:1.25" in a docker-compose sample came back as
        // "image:: nginx:1.25", and a sample about this very conversion had its own placeholder
        // tokens expanded. Nothing has to run before the masking, because pandoc writes the
        // delimiters on lines of their own; masking rather than walking line by line, because
        // several substitutions match across line boundaries and splitting the text changes what
        // they see.
        List<String> listings = new ArrayList<>();
        String masked = mask(adoc, listings);
        return unmask(substituteOutsideListings(masked), listings);
    }

    /** Replaces every verbatim block with a token no substitution matches. */
    private static String mask(String adoc, List<String> listings) {
        Matcher matcher = VERBATIM_BLOCK.matcher(adoc);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(masked,
                    Matcher.quoteReplacement(MASK_PREFIX + listings.size() + MASK_SUFFIX));
            listings.add(matcher.group());
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    private static String unmask(String adoc, List<String> listings) {
        String text = adoc;
        for (int i = 0; i < listings.size(); i++) {
            text = text.replace(MASK_PREFIX + i + MASK_SUFFIX, listings.get(i));
        }
        return text;
    }

    private static String substituteOutsideListings(String adoc) {
        String text = adoc
                .replaceAll("%%CRLF%% *", "\n")
                .replaceAll("(=+) \\[discrete\\]", "[discrete]\n$1 ")
                // U+00A0 NO-BREAK SPACE as the AsciiDoc attribute. Written as an escape so that
                // no editor normalises it to a space here - that would replace every space in the
                // document and break its headings and its lists.
                .replaceAll("\\u00A0", "{nbsp}")
                .replaceAll("(?sm)^ [+] *$", "")
                .replace("%7Bfilepath%7D", "{filepath}")
                .replaceAll("\\s*%%DISCRETE%%\\s*", "\n\n[discrete]\n");
        text = admonitions(text);
        text = collapsibles(text);
        text = anchors(text);
        text = rowHeaderTables(text);
        text = statusBadges(text);
        // Pandoc writes "image:foo[]" even where the image stands on a line of its own, and
        // AsciiDoc needs "image::foo[]" for a block image. Anchored to the start of a line, so
        // that an image inside a sentence stays inline.
        return text.replaceAll("(?m)^(\\s*)image:(?!:)", "$1image::");
    }

    /**
     * An optional title placeholder immediately before the opening one, so that the block gets a
     * blank line before it while its ".title" line stays attached to it - AsciiDoc drops a title
     * that is separated from its block by a blank line.
     */
    private static String admonitions(String adoc) {
        String opened = replaceAll(ADMONITION, adoc, matcher ->
                "\n\n" + titleLine(matcher.group(1)) + "[" + matcher.group(2) + "]\n====\n\n");
        return opened.replaceAll("\\s*%%ADMON-END%%\\s*", "\n\n====\n\n");
    }

    /** Six delimiter characters against the admonition's four, so that either may nest in either. */
    private static String collapsibles(String adoc) {
        String opened = replaceAll(COLLAPSIBLE, adoc, matcher ->
                "\n\n" + titleLine(matcher.group(1)) + "[%collapsible]\n======\n\n");
        return opened.replaceAll("\\s*%%EXPAND-END%%\\s*", "\n\n======\n\n");
    }

    /**
     * A blank line before the anchor so that it is separated from the block above, and a single
     * newline after it so that it stays attached to the block below.
     */
    private static String anchors(String adoc) {
        return replaceAll(ANCHOR, adoc, matcher ->
                // Leading underscore because AsciiDoc wants an id to start with a letter or one,
                // and any "++_++" pandoc wrote into the name spelled back as an underscore.
                "\n\n[[_" + matcher.group(1).replace("++_++", "_") + "]]\n");
    }

    /**
     * The marker a table of row headers carries becomes the column specifier that renders its
     * first column as a header. Pandoc's own specifier, where it wrote one, is consumed with it -
     * a table may carry only one.
     */
    private static String rowHeaderTables(String adoc) {
        return replaceAll(ROW_HEADER_TABLE, adoc, matcher -> {
            int columns = Integer.parseInt(matcher.group(1));
            return "[cols=\"h" + ",1".repeat(columns - 1) + "\"]\n|===";
        });
    }

    /** A coloured badge as an inline role, its colour lower case so the style has a stable name. */
    private static String statusBadges(String adoc) {
        return replaceAll(STATUS, adoc, matcher -> "[.status."
                + matcher.group(1).toLowerCase(Locale.ROOT) + "]#"
                + matcher.group(2).trim() + "#");
    }

    private static String titleLine(String title) {
        return title == null || title.trim().isEmpty() ? "" : "." + title.trim() + "\n";
    }

    private static String replaceAll(Pattern pattern, String text,
                                     Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
