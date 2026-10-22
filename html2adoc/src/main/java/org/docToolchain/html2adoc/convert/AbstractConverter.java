/*
 * Derived from the html2adoc module of https://github.com/jmini/asciidoctorj-experiments
 * (Copyright Jeremie Bresson), licensed under the Apache License, Version 2.0; see
 * LICENSE-APACHE-2.0.txt. Modified for dtc-confluence, see NOTICE.
 */
package org.docToolchain.html2adoc.convert;

import org.docToolchain.html2adoc.context.IContext;

abstract class AbstractConverter implements IConverter {

    private static final String BLANK_OR_NO_BREAK_SPACE = "[\u00A0\\s]*";

    /**
     * Removes the newlines at either end of the content that starts at {@code contentStart}.
     *
     * <p>A block converter ends what it writes with a blank line, which is right between blocks
     * and wrong at the end of something that encloses them: a table cell would carry it past the
     * end of the cell, and a list item would turn a tight list into a page of single-item lists.
     * Only newlines are removed - a leading space can be the indentation of a literal block.</p>
     */
    protected static void trimNewlines(StringBuilder sb, int contentStart) {
        while (sb.length() > contentStart && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        int start = contentStart;
        while (start < sb.length() && sb.charAt(start) == '\n') {
            start = start + 1;
        }
        sb.delete(contentStart, start);
    }

    /**
     * Whether {@code text} is empty or holds nothing but whitespace and {@code &nbsp;}.
     *
     * <p>Exported HTML uses {@code &nbsp;} where a word processor had empty space, so a run of it is
     * layout rather than content. Empty counts as well, because jsoup normalises a lone
     * {@code &nbsp;} to a space and then trims it away, which an older jsoup did not: the HTML that
     * produced the empty string was still a spacer.</p>
     */
    protected static boolean isBlankOrNoBreakSpace(String text) {
        return text.matches(BLANK_OR_NO_BREAK_SPACE);
    }

    /**
     * Writes the blank line that has to stand in front of a block, as far as it is missing.
     *
     * <p>Markup that opens a block is only markup at the start of a line that has a blank line
     * above it. Written at the cursor it is read as part of what it follows, and the block is lost
     * with it: {@code .Title[cols="2*"]} is a sentence rather than a table's title and shape, and
     * {@code lead== Heading} is a line of prose rather than a section.</p>
     *
     * <p>A blank line is enough for every block, the titled ones included: AsciiDoc keeps a
     * {@code .Title} line attached to the block it introduces across one. So no block needs to
     * begin closer to what precedes it than this, and no converter has to know what does.</p>
     */
    protected static void startBlock(StringBuilder sb) {
        if (sb.length() == 0) {
            return;
        }
        if (AdocText.lastCharacterOf(sb) != '\n') {
            sb.append('\n');
        }
        if (sb.length() == 1 || sb.charAt(sb.length() - 2) != '\n') {
            sb.append('\n');
        }
    }

    /**
     * Writes the space that the HTML implied and the output does not have yet.
     *
     * <p>Only on a started line, because a space after a list bullet or a heading marker would be
     * doubled, and only when the output does not already end in one.</p>
     *
     * <p>And never at the start of a line, whatever the context says: an indented line is a literal
     * block to AsciiDoc, so a space written there turns the paragraph that follows into a
     * preformatted one - and it also hides the start of the line from {@code AdocText.escape},
     * which guards block markup such as a leading {@code .Title} only when it sees the newline.
     * {@code isLineStarted} cannot answer this on its own, because the converters that end a line
     * return the context they were given.</p>
     */
    protected void addSpaceIfNeeded(IContext context, StringBuilder sb) {
        if (context.isSpaceNeeded() && context.isLineStarted()
                && AdocText.lastCharacterOf(sb) != '\n'
                && !sb.substring(sb.length() - 1).equals(" ")) {
            sb.append(" ");
        }
    }
}
