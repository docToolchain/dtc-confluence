package org.docToolchain.atlassian.confluence.publish;

/**
 * A file the page refers to, to be attached once the page has an id.
 *
 * <p>The page does not exist yet while its body is being built, so an attachment cannot be sent
 * with it. What the body needs is collected here and uploaded afterwards.</p>
 *
 * @param url             where to read the file from, as a path on this machine. Where
 *                        {@code content} is given this is the file the content came out of, so
 *                        that a message can still say where it came from.
 * @param fileName        the name it is attached under, which the body already refers to
 * @param comment         what the attachment's version comment says about where it came from
 * @param exportedVersion the revision an export took this file from, or
 *                        {@link AttachmentName#NO_VERSION} where the name carries none. Travels
 *                        from the file name to the upload because it is what decides whether
 *                        Confluence still holds the revision this file came from.
 * @param content         the bytes to send, where what is attached is no file on disk at all. The
 *                        diagram inside a picture is one file to the repository and two
 *                        attachments to Confluence, and the second of them exists nowhere but in
 *                        memory. {@code null} for everything else, which is read from {@code url}.
 */
public record Upload(String url, String fileName, String comment, int exportedVersion,
                     byte[] content) {

    /** For a file that is a file, which is where most attachments come from. */
    public Upload(String url, String fileName, String comment, int exportedVersion) {
        this(url, fileName, comment, exportedVersion, null);
    }

    /** For a file whose name says nothing about where it came from. */
    public Upload(String url, String fileName, String comment) {
        this(url, fileName, comment, AttachmentName.NO_VERSION);
    }
}
