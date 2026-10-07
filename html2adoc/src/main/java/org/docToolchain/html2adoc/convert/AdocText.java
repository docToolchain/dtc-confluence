/*
 * Written for dtc-confluence; it has no counterpart in the upstream html2adoc module.
 * Licensed under the Apache License, Version 2.0, the licence of this module; see
 * LICENSE-APACHE-2.0.txt and NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import java.util.regex.Pattern;

/**
 * Makes text survive as text: the one place where the converters hand their text through before
 * writing it, so that a sentence which happens to look like AsciiDoc still reads as a sentence.
 *
 * <p>Escaping is done where it is needed and nowhere else, and the line between the two is drawn by
 * asking what AsciiDoc would do with the text unescaped. The reason is that an unnecessary escape is
 * not merely untidy, it is wrong: AsciiDoc prints the backslash of a {@code \*} that opens no
 * formatting span, so escaping on suspicion turns {@code 2 * 3} into {@code 2 \* 3} on the page.
 * The same mistake in the other direction cost this project the placeholder tokens of
 * {@code org.docToolchain.atlassian.confluence.export.AdocOutput}: pandoc escapes every bracket in
 * every position, and {@code [source, groovy]} reaches the document as
 * {@code ++[++source, groovy++]++}.</p>
 *
 * <p>Every rule below was checked against {@code asciidoctor}: the unescaped form renders as
 * something other than the text that went in, and the escaped form renders as that text.</p>
 */
final class AdocText {

    /** An attribute that resolves to nothing, which is how AsciiDoc is told "this is not markup". */
    private static final String EMPTY_ATTRIBUTE = "{empty}";

    /** The characters AsciiDoc builds inline formatting from. */
    private static final String MARKERS = "*_`#+^~";

    /**
     * The markers with no boundary rules: superscript and subscript are unconstrained, which is why
     * {@code H~2~O} works mid-word, and why a lone {@code ~} in text is dangerous where a lone
     * {@code *} is not.
     */
    private static final String UNCONSTRAINED_MARKERS = "^~";

    /**
     * Characters that cannot stand before a constrained marker, besides a word character.
     *
     * <p>{@code &}, {@code <} and {@code >} are in the list because AsciiDoc replaces them with
     * {@code &amp;}, {@code &lt;} and {@code &gt;} before it looks for formatting, which leaves a
     * {@code ;} in front of the marker.</p>
     */
    private static final String LEFT_BLOCKERS = ";:}&<>";

    /** Same, for the passthrough marker, which AsciiDoc does allow after a closing brace. */
    private static final String LEFT_BLOCKERS_PASSTHROUGH = ";:&<>";

    /** Quotes block the monospace marker on both sides, because they are its curly-quote syntax. */
    private static final String QUOTE_BLOCKERS = "\"'";

    /**
     * An attribute reference. The name may also be a {@code set:} or {@code counter:} expression,
     * which AsciiDoc evaluates for its side effect and would do here too.
     *
     * <p>Every reference of this shape is escaped, not only the ones that resolve. Which of them
     * resolve is a property of the document the AsciiDoc is read in, and this converter does not
     * know that document: {@code {nbsp}} resolves in every one of them, a project's own attribute
     * resolves in its own, and a reference that resolves replaces the words on the page. The cost
     * is a backslash in the source on a shape that prose almost never has - {@code {a b}} and
     * {@code {"a":1}} are not references and keep their braces.</p>
     */
    private static final Pattern ATTRIBUTE_REFERENCE = Pattern.compile(
            "\\{(\\w[\\w-]*|(?:set|counter2?):[^}]+)}", Pattern.UNICODE_CHARACTER_CLASS);

    /** An inline anchor, which AsciiDoc turns into an empty {@code <a id>} and takes off the page. */
    private static final Pattern INLINE_ANCHOR =
            Pattern.compile("\\[\\[[\\w:.-]+(?:,[^\\]]*)?]]");

    /** A line that is nothing but {@code [...]} is a block attribute line and not text. */
    private static final Pattern BLOCK_ATTRIBUTE_LINE =
            Pattern.compile("\\[(?:|[^\\[\\]]*[^\\[\\]\\s]|\\[[^\\[\\]]*])]");

    /** A line that sets a document attribute, which likewise leaves no text behind. */
    private static final Pattern ATTRIBUTE_ENTRY =
            Pattern.compile(":!?\\w[\\w-]*!?:(?:\\s.*)?", Pattern.UNICODE_CHARACTER_CLASS);

    private AdocText() {
    }

    /**
     * The last character written, which is what decides whether a marker may open a span and
     * whether the next text begins a line.
     *
     * @return the last character of {@code written}, or {@code '\n'} when nothing is written yet,
     *         because the start of the output is the start of a line
     */
    static char lastCharacterOf(CharSequence written) {
        return written.length() == 0 ? '\n' : written.charAt(written.length() - 1);
    }

    /**
     * @param text the text of one HTML text node, as it should appear on the page
     * @param previous the last character already written, from {@link #lastCharacterOf}
     * @param cellSeparators the table cell separators in force, empty outside a table
     * @return {@code text} with everything escaped that AsciiDoc would otherwise read as markup
     */
    static String escape(String text, char previous, String cellSeparators) {
        // The order is not free. References are escaped before the formatting scan, so that the
        // "{empty}" the scan may insert is not taken for one of them, and the line start is
        // protected last, so that it stays the first thing on the line.
        String escaped = ATTRIBUTE_REFERENCE.matcher(text).replaceAll(match -> "\\\\" + match.group());
        escaped = INLINE_ANCHOR.matcher(escaped).replaceAll(match -> "\\\\" + match.group());
        escaped = escapeFormatting(escaped, previous);
        escaped = escapeCellSeparators(escaped, cellSeparators);
        if (previous == '\n' && beginsWithBlockMarkup(escaped)) {
            escaped = EMPTY_ATTRIBUTE + escaped;
        }
        return escaped;
    }

    /**
     * Whether AsciiDoc's constrained form of {@code marker} — one character on each side of the
     * span, as in {@code *bold*} — is recognised directly after {@code previous}.
     *
     * <p>A constrained span may only begin at the edge of a word: at the start of a line, or after a
     * character that is neither a word character nor one of {@link #LEFT_BLOCKERS}. Where it may
     * not, the span has to be written in the unconstrained form, {@code **bold**}, which AsciiDoc
     * recognises wherever it stands.</p>
     *
     * <p>There is a mirror rule for the closing side, {@link #constrainedFormWorksBefore}.</p>
     */
    static boolean constrainedFormWorksAfter(char marker, char previous) {
        if (previous == '\n') {
            return true;
        }
        if (isWordCharacter(previous)) {
            return false;
        }
        String blockers = marker == '+' ? LEFT_BLOCKERS_PASSTHROUGH : LEFT_BLOCKERS;
        if (blockers.indexOf(previous) >= 0) {
            return false;
        }
        return marker != '`' || QUOTE_BLOCKERS.indexOf(previous) < 0;
    }

    /**
     * Escapes every marker in {@code text} that would open a formatting span, and only those.
     *
     * <p>A marker that opens nothing is left alone, because a backslash in front of it would be
     * printed. That is why the whole run of markers and the text after it are examined rather than
     * the single character: {@code 2 * 3} opens no span and keeps its asterisk, while
     * {@code a *bold* b} does open one and gets the backslash.</p>
     */
    private static String escapeFormatting(String text, char previous) {
        StringBuilder escaped = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char marker = text.charAt(index);
            int run = MARKERS.indexOf(marker) < 0 ? 0 : runLength(text, index);
            char left = index == 0 ? previous : text.charAt(index - 1);
            if (run > 0 && spanOpensAt(text, index, run, left)) {
                escaped.append('\\').append(marker);
                for (int repeat = 1; repeat < run; repeat++) {
                    // A marker directly behind the escaped one would pair up with it and open the
                    // span after all, so the run is broken apart by an attribute worth nothing.
                    escaped.append(EMPTY_ATTRIBUTE).append(marker);
                }
                index = index + run;
            } else {
                escaped.append(marker);
                index = index + 1;
            }
        }
        return escaped.toString();
    }

    private static boolean spanOpensAt(String text, int start, int run, char left) {
        char marker = text.charAt(start);
        if (UNCONSTRAINED_MARKERS.indexOf(marker) >= 0) {
            return closesWithoutSpace(text, start);
        }
        if (run > 1) {
            // Two or more markers are the unconstrained form, which needs no boundary and only a
            // closing pair with something between.
            return text.indexOf(text.substring(start, start + 2), start + run) > start + run;
        }
        return constrainedSpanOpensAt(text, start, left);
    }

    /**
     * Whether a superscript or subscript opened at {@code start} would find its closing marker.
     *
     * <p>Their content is one or more characters with no space among them, which is what makes
     * {@code a ~ b ~ c} harmless and {@code H~2~O} a span.</p>
     */
    private static boolean closesWithoutSpace(String text, int start) {
        char marker = text.charAt(start);
        for (int index = start + 2; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character)) {
                return false;
            }
            if (character == marker) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a single marker at {@code start} would open a constrained span.
     *
     * <p>Both edges of the span have to sit against a word, and neither may sit against a space:
     * AsciiDoc takes the first closing marker that has a non-space before it and no word character
     * after it. The end of the text counts as such a position, because what the converters write
     * after a text node is a space, the end of a line, or punctuation.</p>
     */
    private static boolean constrainedSpanOpensAt(String text, int start, char left) {
        char marker = text.charAt(start);
        if (!constrainedFormWorksAfter(marker, left)) {
            return false;
        }
        if (start + 1 >= text.length() || Character.isWhitespace(text.charAt(start + 1))) {
            return false;
        }
        for (int index = start + 2; index < text.length(); index++) {
            if (text.charAt(index) != marker || Character.isWhitespace(text.charAt(index - 1))) {
                continue;
            }
            if (constrainedFormWorksBefore(marker, index + 1 < text.length() ? text.charAt(index + 1) : '\n')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether AsciiDoc's constrained form of {@code marker} is still recognised when {@code next}
     * follows the closing character.
     *
     * <p>The closing side of a constrained span may not run into a word either, which is why
     * {@code *bold*word} is not bold. Pass {@code '\n'} for the end of a line, and for anything the
     * converters write that cannot block: a separating space, or punctuation.</p>
     */
    static boolean constrainedFormWorksBefore(char marker, char next) {
        if (next == '\n') {
            return true;
        }
        if (isWordCharacter(next)) {
            return false;
        }
        return marker != '`' || (QUOTE_BLOCKERS + '`').indexOf(next) < 0;
    }

    /**
     * Whether {@code marker} has a constrained form at all, and the two forms therefore have to be
     * chosen between. Superscript and subscript do not: their only form is unconstrained.
     */
    static boolean hasConstrainedForm(char marker) {
        return UNCONSTRAINED_MARKERS.indexOf(marker) < 0;
    }

    /**
     * Escapes the cell separators of the tables this text sits in, so that a {@code |} in a sentence
     * does not end the cell and shift every cell after it.
     *
     * <p>Both separators are escaped inside a nested table, not only its own: the {@code |} of the
     * outer table ends the outer cell from wherever it stands, the inner table included.</p>
     */
    private static String escapeCellSeparators(String text, String cellSeparators) {
        String escaped = text;
        for (char separator : cellSeparators.toCharArray()) {
            escaped = escaped.replace(String.valueOf(separator), "\\" + separator);
        }
        return escaped;
    }

    /**
     * Whether AsciiDoc would read the first characters of a line of text as the start of a block
     * rather than as text.
     *
     * <p>These are the cases that take the text off the page altogether, which is what separates
     * them from the many other characters that merely look like markup: {@code .NET} as the first
     * word of a paragraph becomes the paragraph's title, {@code [source, groovy]} becomes a block
     * attribute line, {@code :name: value} an attribute entry, {@code // remark} a comment, and all
     * four leave nothing behind. A heading, a list item and a block delimiter keep the text but
     * change what it is.</p>
     *
     * <p>Each of {@code = * - +} is only markup in the shapes listed here. Plain words that begin
     * with one — {@code -foo}, {@code *foo}, {@code =foo} — are paragraphs to AsciiDoc as they
     * stand, and are left alone rather than given a protection that would show in the source for
     * nothing.</p>
     */
    private static boolean beginsWithBlockMarkup(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        switch (first) {
            case '.':
                // Either a block title or, with a space, an ordered list item.
                return true;
            case '=':
            case '*':
            case '-':
            case '+':
                return text.length() == 1 || Character.isWhitespace(text.charAt(1)) || isDelimiterLine(text);
            case '[':
                return BLOCK_ATTRIBUTE_LINE.matcher(text).matches();
            case ':':
                return ATTRIBUTE_ENTRY.matcher(text).matches();
            case '/':
                return text.startsWith("//");
            default:
                return false;
        }
    }

    /** Whether {@code text} is nothing but its first character repeated, which is a delimiter line. */
    private static boolean isDelimiterLine(String text) {
        return text.length() > 1 && runLength(text, 0) == text.length();
    }

    private static int runLength(String text, int start) {
        char character = text.charAt(start);
        int end = start;
        while (end < text.length() && text.charAt(end) == character) {
            end = end + 1;
        }
        return end - start;
    }

    /** A word character as AsciiDoc understands it, which includes letters outside ASCII. */
    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }
}
