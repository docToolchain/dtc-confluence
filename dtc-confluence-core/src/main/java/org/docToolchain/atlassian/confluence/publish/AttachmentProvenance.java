package org.docToolchain.atlassian.confluence.publish;

/**
 * Answers the one question that decides whether an attachment may be written over: has somebody
 * else touched it since this publisher last did?
 *
 * <p>Two things can answer it, and either is enough:</p>
 *
 * <ul>
 *   <li>The revision an export took the file from. Where Confluence still holds exactly that
 *       revision, nobody has written it since the export, so writing it is writing over our own
 *       material.</li>
 *   <li>The hash in the current revision's comment. Only an upload of ours puts one there, so a
 *       comment carrying one says this publisher wrote what Confluence holds now.</li>
 * </ul>
 *
 * <p>Where neither answers, the attachment is somebody else's: a file created in Confluence that
 * no export ever saw has no version to compare against, and unknown means not ours.</p>
 *
 * <p>What this replaces: the comment hash alone was read as "has it changed", and anything that
 * did not match was overwritten. Measured against a real instance, the draw.io app writes its own
 * version comment - {@code draw.io diagram} - which carries no hash, so no comparison could ever
 * match it and every publish silently overwrote the user's in-Confluence edit.</p>
 *
 * @param exportedVersion the revision the file name came from, or {@link AttachmentName#NO_VERSION}
 * @param remoteVersion   the revision Confluence holds, or {@link #UNKNOWN_VERSION} where the
 *                        answer did not report one
 * @param remoteHash      the hash the current revision's comment carries, empty where it carries
 *                        none
 */
public record AttachmentProvenance(int exportedVersion, int remoteVersion, String remoteHash) {

    /** What a Confluence answer without a version number amounts to; versions start at 1. */
    public static final int UNKNOWN_VERSION = 0;

    /**
     * @return whether Confluence already holds the bytes that would be uploaded, so that an
     *         upload would bump the version and notify every watcher for nothing
     */
    public boolean holdsOurContent(String localHash) {
        return remoteHash.equals(localHash);
    }

    /**
     * @return whether this run may write over what Confluence holds
     */
    public boolean isOurs() {
        return writtenByUs() || isTheRevisionWeExported();
    }

    /** Only an upload of this publisher puts a hash in the version comment. */
    private boolean writtenByUs() {
        return !remoteHash.isEmpty();
    }

    /**
     * Equality, not "no higher than": an attachment deleted and attached again counts from one, so
     * a revision below the exported one is not the revision we exported either. A version
     * Confluence does not report equals nothing, which is the same answer.
     */
    private boolean isTheRevisionWeExported() {
        return exportedVersion != AttachmentName.NO_VERSION && remoteVersion == exportedVersion;
    }
}
