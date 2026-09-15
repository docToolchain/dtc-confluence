package org.docToolchain.atlassian.confluence.publish;

/**
 * A file the page refers to, to be attached once the page has an id.
 *
 * <p>The page does not exist yet while its body is being built, so an attachment cannot be sent
 * with it. What the body needs is collected here and uploaded afterwards.</p>
 *
 * @param url      where to read the file from, as a path on this machine
 * @param fileName the name it is attached under, which the body already refers to
 * @param comment  what the attachment's version comment says about where it came from
 */
public record Upload(String url, String fileName, String comment) {
}
