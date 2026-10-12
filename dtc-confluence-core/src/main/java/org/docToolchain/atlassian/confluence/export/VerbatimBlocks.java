package org.docToolchain.atlassian.confluence.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a substitution to everything in a document except its verbatim blocks.
 *
 * <p>AsciiDoc substitutes nothing inside a listing or a literal block, so neither may the export:
 * "image: nginx:1.25" in a docker-compose sample came back as "image:: nginx:1.25", and a page of
 * ours documenting this conversion had its own placeholder tokens expanded.</p>
 *
 * <p>By masking rather than by walking line by line, because several of the substitutions match
 * across line boundaries and splitting the text changes what they see.</p>
 */
final class VerbatimBlocks {

    /**
     * A verbatim block: a delimiter line, everything up to the next line of the same delimiter,
     * and that line. Hyphens for a listing block and dots for a literal one - pandoc writes the
     * latter for a sample whose language Confluence did not record. Reluctant, so two blocks are
     * two matches rather than one spanning both.
     */
    private static final Pattern VERBATIM_BLOCK = Pattern.compile(
            "(?m)^[ \\t]*(-{4,}|\\.{4,})[ \\t]*$.*?^[ \\t]*\\1[ \\t]*$", Pattern.DOTALL);

    /** Around a masked block: NUL cannot occur in a Confluence page and matches no pattern here. */
    private static final String MASK_PREFIX = "\u0000verbatim";
    private static final String MASK_SUFFIX = "\u0000";

    private VerbatimBlocks() {
    }

    /**
     * @param adoc         the document
     * @param substitution what to apply to the rest of it
     * @return the document with the substitution applied outside its verbatim blocks, and those
     *         blocks as they were
     */
    static String outside(String adoc, UnaryOperator<String> substitution) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = VERBATIM_BLOCK.matcher(adoc);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(masked,
                    Matcher.quoteReplacement(MASK_PREFIX + blocks.size() + MASK_SUFFIX));
            blocks.add(matcher.group());
        }
        matcher.appendTail(masked);

        String text = substitution.apply(masked.toString());
        for (int i = 0; i < blocks.size(); i++) {
            text = text.replace(MASK_PREFIX + i + MASK_SUFFIX, blocks.get(i));
        }
        return text;
    }
}
