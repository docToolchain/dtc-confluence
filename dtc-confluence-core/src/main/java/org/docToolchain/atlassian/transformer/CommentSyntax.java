package org.docToolchain.atlassian.transformer;

import java.util.Map;
import java.util.Set;

/**
 * The comment characters a language actually uses, for the languages the Confluence code macro
 * knows.
 *
 * <p>docToolchain wrote {@code //} into every language, which is wrong in a shell, in XML, in YAML,
 * in a properties file and in SQL - and a callout is meant to be ignorable by whatever reads the
 * code, so the wrong character defeats the purpose.</p>
 */
final class CommentSyntax {

    private static final String DEFAULT_PREFIX = "//";

    private static final Map<String, String> LINE_PREFIX = Map.ofEntries(
            Map.entry("bash", "#"),
            Map.entry("powershell", "#"),
            Map.entry("py", "#"),
            Map.entry("ruby", "#"),
            Map.entry("perl", "#"),
            Map.entry("yml", "#"),
            Map.entry("sql", "--"),
            Map.entry("applescript", "--"),
            Map.entry("erl", "%"),
            Map.entry("vb", "'"),
            Map.entry("text", "#"));

    /** Languages with no line comment at all; a block comment has to be closed again. */
    private static final Map<String, String[]> BLOCK = Map.of(
            "xml", new String[] {"<!--", "-->"},
            "css", new String[] {"/*", "*/"},
            "sass", new String[] {"/*", "*/"},
            // ColdFusion's comment is <!--- ---> - three dashes, and it has to be closed.
            "coldfusion", new String[] {"<!---", "--->"});

    /**
     * Where a trailing backslash continues the line, a comment after it breaks the command.
     *
     * <p>PowerShell is not one of them: it continues a line with a backtick, and a trailing
     * backslash there is an ordinary path separator. Treating it as a continuation would move a
     * PowerShell block off the configured style for no reason.</p>
     */
    private static final Set<String> CONTINUES_WITH_BACKSLASH = Set.of("bash");

    private CommentSyntax() {
    }

    /**
     * @return what to put in front of a callout marker so the language ignores it
     */
    static String opening(String language) {
        String[] block = BLOCK.get(language);
        return block != null ? block[0] : LINE_PREFIX.getOrDefault(language, DEFAULT_PREFIX);
    }

    /**
     * @return what to put after it, empty for a language with line comments
     */
    static String closing(String language) {
        String[] block = BLOCK.get(language);
        return block != null ? " " + block[1] : "";
    }

    /**
     * @return whether a callout in this language can follow a line continuation
     */
    static boolean brokenByLineContinuation(String language) {
        return CONTINUES_WITH_BACKSLASH.contains(language);
    }
}
