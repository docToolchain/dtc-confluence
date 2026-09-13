package org.docToolchain.atlassian.transformer;

import org.jsoup.nodes.Element;

/**
 * Replaces highlighted text with inline styling.
 */
public class MarkTransformer {

    /** Confluence strips mark elements, so the highlight is expressed as a style instead. */
    private static final String HIGHLIGHT = "<span style=\"background:#ff0;color:#000\"></style>";

    public void transformMarks(Element body) {
        body.select("mark").wrap(HIGHLIGHT).unwrap();
    }
}
