package org.docToolchain.atlassian.confluence.publish;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An attachment's file name as an export of Confluence wrote it: the name Confluence knows it
 * under, and the revision the export took it from.
 *
 * <p>An export writes {@code images/<folders of the page>/<page>/<version>_<filename>}, because a
 * document may refer to an older revision of a diagram. That prefix is provenance, not a name.
 * Publishing {@code 3_diagram.png} back under that name renames the attachment, and the rename is
 * not cosmetic: the draw.io app binds a diagram by exact attachment name, the diagram itself
 * carries no file extension at all and its preview is {@code <name>.png}, so a renamed attachment
 * leaves the macro pointing at nothing.</p>
 *
 * <p>The version is kept rather than dropped, because the name is the only thing that tells the
 * publish side which revision this file came from - and that is what decides whether somebody
 * else has written the attachment since.</p>
 *
 * @param fileName        the name the attachment is published under
 * @param exportedVersion the revision the name carried, or {@link #NO_VERSION} for a name that
 *                        carries none - a file an author put in the repository themselves
 */
public record AttachmentName(String fileName, int exportedVersion) {

    /** What a name that no export wrote carries instead of a version. */
    public static final int NO_VERSION = 0;

    /**
     * Digits and an underscore, which is what the export writes. At most nine digits so the
     * number fits an {@code int}; a longer run of them is a file name rather than a version.
     *
     * <p>It cannot be told apart from a file an author named {@code 2024_report.png}, which this
     * publishes as {@code report.png}. The page refers to it under the same name, so the page
     * still shows it; see {@code docs/decisions.adoc}.</p>
     */
    private static final Pattern EXPORTED = Pattern.compile("^(\\d{1,9})_(.+)$");

    /**
     * @param name the last segment of what the document refers to, already URL-decoded
     */
    public static AttachmentName of(String name) {
        Matcher matcher = EXPORTED.matcher(name);
        return matcher.matches()
                ? new AttachmentName(matcher.group(2), Integer.parseInt(matcher.group(1)))
                : new AttachmentName(name, NO_VERSION);
    }
}
