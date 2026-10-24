package org.docToolchain.atlassian.confluence.export;

import java.util.Optional;

/**
 * Draws a picture of a diagram, where anything on this machine can.
 *
 * <p>An interface because the export must work without one: no renderer is the normal case, and
 * the export is not allowed to need draw.io. What a missing renderer costs is one stale picture
 * inside an exported file whose diagram is current anyway - see {@link DiagramMerge}.</p>
 */
public interface DiagramRenderer {

    /**
     * @param diagram the {@code mxfile} to draw
     * @param aspect  the id of the page of it to draw, empty for the file's first page
     * @return the PNG, or empty where it could not be drawn - which is never a failure of the
     *         export, only one picture it could not refresh
     */
    Optional<byte[]> render(String diagram, String aspect);
}
