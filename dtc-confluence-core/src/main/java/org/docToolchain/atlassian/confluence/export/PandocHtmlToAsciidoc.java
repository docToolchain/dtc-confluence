package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;

/**
 * Converts by shelling out to pandoc, which has to be on the PATH.
 *
 * <p>Pandoc writes AsciiDoc but escapes the punctuation AsciiDoc is built from, which is why
 * {@link AdocOutput} runs after it and why everything the export needs to arrive as markup travels
 * through here as a {@code %%TOKEN%%} rather than as the markup itself.</p>
 */
public class PandocHtmlToAsciidoc implements HtmlToAsciidoc {

    /**
     * {@inheritDoc}
     *
     * <p>{@code --wrap preserve} keeps the line breaks of the input, and {@code -s} asks for a
     * document rather than a fragment.</p>
     */
    @Override
    public boolean convert(File html, File adoc) throws IOException {
        ProcessBuilder pandoc = new ProcessBuilder("pandoc", "--wrap", "preserve",
                "-f", "html", "-t", "asciidoc", "-s",
                html.getCanonicalPath(), "-o", adoc.getCanonicalPath());
        pandoc.inheritIO();
        try {
            // Above 1, because pandoc reports a document it had to guess at with 1 and still
            // writes it.
            if (pandoc.start().waitFor() > 1) {
                System.out.println("couldn't convert " + html.getCanonicalPath());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("couldn't convert " + html.getCanonicalPath());
            return false;
        }
    }
}
