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
     */
    protected void addSpaceIfNeeded(IContext context, StringBuilder sb) {
        if (context.isSpaceNeeded() && context.isLineStarted() && !sb.substring(sb.length() - 1).equals(" ")) {
            sb.append(" ");
        }
    }
}
