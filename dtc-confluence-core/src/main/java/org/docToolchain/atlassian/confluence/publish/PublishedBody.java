package org.docToolchain.atlassian.confluence.publish;

import java.util.List;

/**
 * A page body ready to be sent, and the files it refers to.
 *
 * @param storageFormat the body in Confluence storage format
 * @param uploads       what has to be attached once the page has an id
 */
public record PublishedBody(String storageFormat, List<Upload> uploads) {
}
