package org.docToolchain.atlassian.confluence.clients;

/**
 * The attachment Confluence holds, as much of it as deciding whether to write it needs.
 *
 * <p>The two REST APIs report it differently - v1 nests the comment under {@code extensions},
 * v2 carries it directly - so each client reads its own answer into this, and nothing above them
 * has to know which API it is talking to.</p>
 *
 * @param id      the attachment's id, which is where a new revision of it is posted
 * @param version the revision Confluence holds, or {@code 0} where the answer reported none
 * @param comment the current revision's version comment, which is where an upload of this
 *                publisher leaves its content hash - and where another writer leaves whatever it
 *                likes
 */
public record RemoteAttachment(String id, int version, String comment) {
}
