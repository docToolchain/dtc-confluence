package org.docToolchain.atlassian.confluence.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A picture that names which view of a shared diagram it shows.
 *
 * <p>A generator can draw several views of one model into a single {@code .drawio} file and export
 * a flat picture per view. Those pictures carry no diagram inside them - draw.io can embed only
 * the whole model, never a pointer into it, and a static picture renders one view whatever it
 * holds - so the only thing left to say which view a picture is of is its name:
 * {@code <diagram>-<view>.<extension>}, as in {@code architecture-core.svg}.</p>
 *
 * <p>The document says nothing about Confluence while using it:
 * {@code image::architecture-core.svg[]} is an ordinary image line, and Asciidoctor, GitHub Pages
 * and an editor's preview all show the flat picture.</p>
 *
 * <h2>Why a plain hyphen is safe</h2>
 *
 * <p>A hyphen is ordinary inside a file name, so the name alone cannot decide anything: read
 * eagerly, every {@code build-pipeline.png} would turn into a view of a diagram nobody drew. The
 * name is therefore never read alone. It only offers {@link #candidatesIn candidates}, and what
 * settles it is whether the diagram they name is actually <em>there</em>, with a page that
 * actually answers to the view. A picture with no such file beside it is a picture.</p>
 *
 * <p>That also sorts out a name with more than one hyphen. {@code architecture-view-core.svg}
 * could be the {@code core} view of {@code architecture-view} or the {@code view-core} view of
 * {@code architecture}, and the directory knows which: whichever file exists wins, longest
 * diagram name first, because the view is the tail.</p>
 *
 * @param diagramName what the diagram is called, which is the attachment the app binds to
 * @param aspect      the page of it this picture shows
 */
record ViewReference(String diagramName, String aspect) {

    /** What separates the diagram from the view it is a picture of. */
    private static final char SEPARATOR = '-';

    /** What the diagram itself is called beside the pictures of it. */
    private static final String SOURCE_EXTENSION = ".drawio";

    /** The pages of an {@code mxfile}, which is what an aspect has to name. */
    private static final Pattern DIAGRAM_ID =
            Pattern.compile("<diagram\\b[^>]*\\bid=\"([^\"]*)\"");

    /**
     * Every way the name could be read, which one of them is right the directory decides.
     *
     * @param fileName the name the document refers to, extension and all
     * @return the readings, the longest diagram name first, or empty where the name carries no
     *         separator at all
     */
    static List<ViewReference> candidatesIn(String fileName) {
        String named = withoutExtension(fileName);
        List<ViewReference> candidates = new ArrayList<>();
        for (int at = named.lastIndexOf(SEPARATOR); at > 0; at = named.lastIndexOf(SEPARATOR,
                at - 1)) {
            String aspect = named.substring(at + 1);
            if (!aspect.isEmpty()) {
                candidates.add(new ViewReference(named.substring(0, at), aspect));
            }
        }
        return candidates;
    }

    /**
     * @return the file beside the picture that holds the diagram itself, which is what gets
     *         attached and what every view of it is drawn from
     */
    String sourceFileName() {
        return diagramName + SOURCE_EXTENSION;
    }

    /**
     * What this picture's view is called inside the diagram.
     *
     * <p>The name of a picture and the id of a page do not have to be spelled the same. A
     * generator may write its pages as {@code view-core} and export them as
     * {@code architecture-core.svg}, which is a reasonable thing to do and would otherwise force
     * this to know that generator's prefix. So the file is asked instead: the aspect is whichever
     * page the name points at, and a page is pointed at by its whole id or by its tail.</p>
     *
     * <p>Only where exactly one page answers. Two pages whose ids both end the same way are a
     * name this cannot resolve, and guessing between them would pin a spot to the wrong view -
     * which reads as a page that is simply wrong rather than as a page that is missing.</p>
     *
     * @param diagram the contents of the {@code .drawio}
     * @return the id to pin a macro to, or empty where no single page answers to the name
     */
    Optional<String> pageOf(String diagram) {
        List<String> ids = new ArrayList<>();
        Matcher found = DIAGRAM_ID.matcher(diagram);
        while (found.find()) {
            ids.add(found.group(1));
        }
        if (ids.contains(aspect)) {
            return Optional.of(aspect);
        }
        List<String> ending = ids.stream().filter(id -> id.endsWith(SEPARATOR + aspect)).toList();
        return ending.size() == 1 ? Optional.of(ending.get(0)) : Optional.empty();
    }

    private static String withoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? fileName : fileName.substring(0, lastDot);
    }
}
