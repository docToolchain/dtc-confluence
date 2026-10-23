package org.docToolchain.atlassian.confluence.export;

/**
 * Where the Jira is that a {@code jira} macro names.
 *
 * <p>The macro identifies its server by a UUID and a display name - {@code ASF JIRA} - and carries
 * no URL. Without one there is no link to write, and the key of an issue is all an export can say.
 * This is what turns the UUID into {@code https://issues.apache.org/jira}.</p>
 *
 * <p>An interface rather than a method on the reader, because the translation has to run without a
 * server: the specifications convert storage format straight to HTML, and so does the corpus the
 * native converter is measured against.</p>
 */
public interface JiraServers {

    /** Resolves nothing, which leaves an issue key as text rather than as a link. */
    JiraServers NONE = (serverId, pageId) -> null;

    /**
     * @param serverId the {@code serverId} parameter of the macro, which is a UUID
     * @param pageId   the page the macro stands on, which is where the answer can be read
     * @return the base URL of that Jira, without a trailing slash, or {@code null} if it cannot
     *         be established
     */
    String baseUrlFor(String serverId, String pageId);
}
