package org.docToolchain.atlassian.confluence.export;

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
    private static final Pattern BLOCK_TITLE =
            Pattern.compile("\\s*%%BLOCK-TITLE%%([^%]+)%%BLOCK-TITLE-END%%\\s*");
    private static final Pattern ROW_HEADER_TABLE = Pattern.compile(
            "%%TABLE-ROWHEADER-(\\d+)%%\\s*\\n+\\s*(?:\\[[^\\]]*\\]\\s*\\n)?\\|===");
    /**
     * What a passthrough block travels through pandoc as, and everything between.
     *
     * <p>The delimiters cannot travel as themselves: pandoc writes a plus in text as
     * {@code {plus}}, so "++++" arrived as "{plus}{plus}{plus}{plus}", which is not a
     * passthrough block - the iframe of a PDF or of a chart was shown to the reader as text.</p>
     */
    private static final Pattern PASSTHROUGH = Pattern.compile(
            "%%PASSTHROUGH-BEGIN%%([\\s\\S]*?)%%PASSTHROUGH-END%%");

    /**
     * What pandoc escaped: "++_++" for an underscore, "{plus}" for a plus - and more than one
     * character where they stood next to each other, as "++><++" for the "><" of a closing tag.
     */
    private static final Pattern PANDOC_ESCAPE = Pattern.compile("\\+\\+(.+?)\\+\\+");

    /** What may not stand at the start of an id: neither a letter nor a digit, so markup or worse. */
    private static final Pattern LEADING_NON_WORD = Pattern.compile("^[^\\p{Alnum}]+");

    /** Two underscores or more, which is AsciiDoc's unconstrained emphasis wherever it stands. */
    private static final Pattern UNDERSCORE_RUN = Pattern.compile("_{2,}");

    private static final Pattern STATUS = Pattern.compile(
            "%%STATUS-BEGIN-(\\w+)%%([\\s\\S]*?)%%STATUS-END%%");

    private AdocOutput() {
    }

    /**
     * @param adoc what pandoc wrote
     * @return the same document with every placeholder spelled out as AsciiDoc
     */
    static String substitute(String adoc) {
        return VerbatimBlocks.outside(adoc, AdocOutput::substituteOutsideListings);
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
        text = passthroughs(text);
        text = admonitions(text);
        text = collapsibles(text);
        text = anchors(text);
        text = blockTitles(text);
        text = rowHeaderTables(text);
        text = statusBadges(text);
        // Pandoc writes "image:foo[]" even where the image stands on a line of its own, and
        // AsciiDoc needs "image::foo[]" for a block image. Anchored to the start of a line, so
        // that an image inside a sentence stays inline.
        return text.replaceAll("(?m)^(\\s*)image:(?!:)", "$1image::");
    }

    /**
     * A passthrough block, with what pandoc escaped inside it spelled back.
     *
     * <p>Nothing inside such a block is AsciiDoc, so nothing in it may carry an AsciiDoc escape:
     * the markup reaches the renderer as it is. A file name keeps its underscore, and a tag its
     * angle brackets.</p>
     */
    private static String passthroughs(String adoc) {
        return replaceAll(PASSTHROUGH, adoc, matcher -> "++++\n"
                + PANDOC_ESCAPE.matcher(matcher.group(1)).replaceAll("$1").replace("{plus}", "+")
                + "\n++++");
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
                // Whatever pandoc escaped in the name is spelled back first, and by the same
                // pattern the passthroughs use: it escapes a run as one group, so a name holding
                // "__" arrives as "++__++" and a literal replace of "++_++" would miss it.
                "\n\n[[" + anchorId(PANDOC_ESCAPE.matcher(matcher.group(1)).replaceAll("$1"))
                        + "]]\n");
    }

    /**
     * An anchor id as both the anchor and every reference to it spell it.
     *
     * <p>Exactly one leading underscore, because AsciiDoc wants an id to start with a letter or
     * one - and because more than one is markup. A run of two underscores is AsciiDoc's
     * unconstrained emphasis, and two such runs on one line pair up: the footnote markers of a
     * page sit on one line, so {@code #__footnotedef_2 … #__footnotedef_1} is read as a span and
     * the two references come out as {@code #<em>footnotedef_2} and {@code #</em>footnotedef_1},
     * links to anchors that do not exist. AsciiDoctor's own ids already begin with an underscore,
     * so adding one was enough to produce that.</p>
     *
     * <p>A single underscore is only safe while a word character follows it: {@code _} before a
     * hyphen and after a non-space is a *closing* constrained emphasis marker, so {@code #_-beta}
     * pairs with the {@code _} of an id earlier on the line just as a double run does. The leading
     * run of anything that is neither a letter nor a digit is therefore dropped, and one
     * underscore put in front of what remains - {@code _footnotedef_1} unchanged, {@code __notes}
     * to {@code _notes}, {@code -beta} to {@code _beta}.</p>
     *
     * <p>A run inside the name is no safer than one at the start: {@code #_a__b} and
     * {@code #_c__d} on one line come out as {@code #_a<em>b} and {@code #_c</em>d}. Every run is
     * therefore collapsed to one underscore. That can make two names one id - {@code a__b} and
     * {@code a_b} both become {@code _a_b} - which is the price: an id that collides is found by
     * whoever wrote both anchors, an id that is markup is a dead link in every page that refers
     * to it.</p>
     *
     * <p>No escape helps here, which is worth stating because escaping is the obvious reflex: a
     * backslash is consumed where it prevents markup and printed where it does not, so escaping
     * both runs leaves the second reference reading {@code #\__footnotedef_1}, and a passthrough
     * around the target stops the macro being a macro.</p>
     */
    static String anchorId(String name) {
        String identifying = LEADING_NON_WORD.matcher(name).replaceFirst("");
        return "_" + UNDERSCORE_RUN.matcher(identifying).replaceAll("_");
    }

    /**
     * The title of the block below it.
     *
     * <p>A blank line above, so that the title is separated from whatever block precedes it, and a
     * single newline after, so that AsciiDoc keeps it attached to the block it titles.</p>
     */
    private static String blockTitles(String adoc) {
        return replaceAll(BLOCK_TITLE, adoc, matcher -> "\n\n." + matcher.group(1).trim() + "\n");
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
