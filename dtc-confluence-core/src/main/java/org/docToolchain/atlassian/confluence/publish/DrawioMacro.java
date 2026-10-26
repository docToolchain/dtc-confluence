package org.docToolchain.atlassian.confluence.publish;

import org.jsoup.nodes.Entities;

/**
 * The storage format of a draw.io macro: the two things it needs, and nothing else.
 *
 * <p>Measured against Confluence Data Center 9.2.21, by publishing a macro carrying only
 * {@code diagramName} and reading back what the server resolved it to. The app filled in
 * {@code links: auto}, {@code tbstyle: top}, {@code border: true}, {@code lbox: true},
 * {@code maxScale: 1} and a {@code macroId} of its own - the same values the app writes out
 * explicitly when a human draws a diagram. So those parameters say nothing a reader of the page
 * would notice, and a publisher that wrote them would be inventing numbers it does not know.</p>
 *
 * <p>{@code diagramWidth} is the exception and is kept, because it is the one parameter that
 * reaches the page: without it the rendered container carries a literal
 * {@code width:${diagramWidth}px} - the placeholder, unsubstituted - and the export view, which is
 * what a PDF is made of, carries {@code style="px;max-width: 100%;"}. It is set to the width the
 * document asked of the picture, so that the same {@code image::} line puts the diagram where it
 * put the picture.</p>
 *
 * <p>{@code height} is kept for the same reason, and only because measuring said so: the rendered
 * container is {@code width:Wpx;height:Hpx}, and a macro without it leaves the literal
 * {@code height:${diagramHeight}px} in the page - two of them, counted on a page published without
 * it, against none on either page the app wrote. It is the picture's own shape at that width,
 * which is the only honest number available: the diagram's natural size is nowhere in the file the
 * document names.</p>
 *
 * <p>No {@code ac:macro-id} either. Confluence assigns one when the page is saved, and a
 * publisher cannot invent a stable one: a fresh id on every run would change the body, and the
 * page would be rewritten every publish for no change at all. Two macros sharing a derived id
 * would be worse.</p>
 */
final class DrawioMacro {

    private DrawioMacro() {
    }

    /**
     * @param diagramName the attachment the app binds to, which is the diagram itself and carries
     *                    no file extension
     * @param width       how wide the document wants it
     * @param height       how tall it is at that width, or empty where the preview's shape could
     *                     not be read - better no parameter than a made-up one
     * @return the macro, as a fragment that is parsed again
     */
    static String showing(String diagramName, String width, String height) {
        // Escaped, because the fragment is parsed again and the name comes from a file name: a
        // quote in it would close the parameter and leave the macro naming a diagram nobody
        // attached. Every parameter carries text, never nothing: jsoup's HTML parser does not
        // close an empty self-closing ac:parameter, so one would swallow the parameters behind
        // it - and Confluence answers a parameter inside a parameter with a 500.
        return withParameters(diagramName, width, height, "");
    }

    /**
     * The same macro, pinned to one view of a diagram that holds several.
     *
     * <p>{@code aspect} is the {@code id} of the page to open on. Measured on Confluence Data
     * Center 9.2.21 it sets the view the spot <em>arrives</em> at and does not lock it - no
     * parameter does. The one setting that would, {@code simpleViewer=true}, renders the macro as
     * a bare {@code <img>} at a preview that has to exist, which gives up the editing this is
     * for. What survives is what matters for reading: every spot opens on its own view, two spots
     * on one page hold apart, and a reload puts a reader who paged away back on the pinned
     * view.</p>
     *
     * <p>No {@code tbstyle} either, because it buys nothing. All five values the app was offered
     * - {@code top}, {@code hidden}, {@code inline}, {@code hover}, {@code float} - come back
     * live and {@code editable}, and read off the page they are indistinguishable but for
     * {@code inline}: a diagram sits there as a plain picture, and the toolbar with the page
     * arrows <em>and the pencil</em> appears when the pointer goes over it. {@code hidden} does
     * not hide it; it only hides it until it is wanted, which is what the others do too.</p>
     *
     * <p>{@code inline} is the one that differs, and it is the one to avoid: there the toolbar
     * stays away and no way into the editor could be found. That is the trade this macro must
     * never make - these diagrams are published rather than drawn as pictures precisely so that
     * people who cannot touch the repository can edit them.</p>
     *
     * <p>{@code links=none} is written although the app would fill in {@code auto}, and it is the
     * one deliberate exception to this class's rule of not writing what the app decides for
     * itself. A generated diagram draws its own cross-view navigation into the picture - a box
     * reading "&#8592; System Context" is a real link - and with {@code auto} one click turns a
     * spot pinned to one view into another, and leaves it there. That is the difference between a
     * reader wandering and a page showing the wrong thing.</p>
     *
     * @param aspect the id of the page of the diagram this spot shows
     */
    static String pinnedTo(String diagramName, String width, String height, String aspect) {
        return withParameters(diagramName, width, height,
                "<ac:parameter ac:name=\"aspect\">" + Entities.escape(aspect) + "</ac:parameter>"
                        + "<ac:parameter ac:name=\"links\">none</ac:parameter>");
    }

    private static String withParameters(String diagramName, String width, String height,
                                         String extra) {
        return "<ac:structured-macro ac:name=\"drawio\" ac:schema-version=\"1\">"
                + "<ac:parameter ac:name=\"diagramName\">" + Entities.escape(diagramName)
                + "</ac:parameter>"
                + "<ac:parameter ac:name=\"diagramWidth\">" + Entities.escape(width)
                + "</ac:parameter>"
                + (height.isEmpty() ? ""
                        : "<ac:parameter ac:name=\"height\">" + Entities.escape(height)
                                + "</ac:parameter>")
                + extra
                + "</ac:structured-macro>";
    }
}
