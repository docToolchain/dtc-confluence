package org.docToolchain.atlassian.confluence.publish;

import java.util.Locale;
import java.util.Map;

/**
 * What a run does with an attachment that somebody else wrote.
 *
 * <p>The author's decision, not ours: a project that publishes diagrams nobody edits in Confluence
 * wants the write, and one whose diagrams are edited there wants its edits kept.</p>
 */
public enum ForeignAttachments {

    /**
     * Say what is in the way and stop, as a page whose title is taken already does. The default,
     * because the alternative loses somebody's work and says so in a line that scrolls past.
     */
    FAIL,

    /** Leave the attachment as it is, name it, and carry on with the rest of the document. */
    SKIP,

    /** Write it anyway. What every run did before the guard existed. */
    OVERWRITE;

    private static final Map<String, ForeignAttachments> BY_NAME = Map.of(
            "fail", FAIL,
            "skip", SKIP,
            "overwrite", OVERWRITE);

    /**
     * @param configured the value of {@code confluence.foreignAttachments}, or {@code null} where
     *                   it is unset
     * @return what it names, or {@link #FAIL} where nothing usable was configured
     */
    public static ForeignAttachments from(Object configured) {
        if (configured == null) {
            return FAIL;
        }
        String name = String.valueOf(configured).trim().toLowerCase(Locale.ROOT);
        ForeignAttachments choice = BY_NAME.get(name);
        if (choice == null) {
            System.out.println(">>> WARN: confluence.foreignAttachments is '" + configured
                    + "', which is none of " + String.join(", ", BY_NAME.keySet())
                    + ". Falling back to fail.");
            return FAIL;
        }
        return choice;
    }
}
