package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The second half of an export: what turns the prepared XHTML of one page into AsciiDoc.
 *
 * <p>There are two answers to that, which is why it is an interface. {@link PandocHtmlToAsciidoc}
 * shells out to pandoc - what the export has always done, and what works today.
 * {@link NativeHtmlToAsciidoc} calls {@code dtc-confluence-html2adoc}, which runs in process and
 * needs nothing installed, and is a proof of concept. {@code confluence.export.converter} picks
 * one; see {@code docs/html2adoc-poc.adoc} for what the two produce on the same input.</p>
 *
 * <p>Files rather than strings, because pandoc reads and writes them and
 * {@link ConfluenceConverter#writePage} has already put the XHTML on disk by the time this runs.
 * Passing text would mean copying it back out to a temporary file for pandoc's sake.</p>
 */
public interface HtmlToAsciidoc {

    /** The setting that shells out to pandoc. The default, because it is what is proven. */
    String PANDOC = "pandoc";

    /** The setting that converts in process, through {@code dtc-confluence-html2adoc}. */
    String NATIVE = "native";

    /**
     * @param name the value of {@code confluence.export.converter}; empty selects {@link #PANDOC}
     * @throws IllegalStateException where the name is neither, because silently falling back to
     *         pandoc would hide a typo behind output that looks right
     */
    static HtmlToAsciidoc named(String name) {
        if (name == null || name.isEmpty() || PANDOC.equals(name)) {
            return new PandocHtmlToAsciidoc();
        }
        if (NATIVE.equals(name)) {
            return new NativeHtmlToAsciidoc();
        }
        throw new IllegalStateException("confluence.export.converter is '" + name + "'; it has to "
                + "be one of " + List.of(PANDOC, NATIVE));
    }

    /**
     * Converts {@code html} and writes the AsciiDoc to {@code adoc}.
     *
     * @return whether a document was written; where it was not, the page is left unconverted and
     *         the reason has been printed
     */
    boolean convert(File html, File adoc) throws IOException;
}
