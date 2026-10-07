package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.docToolchain.html2adoc.Html2Adoc;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/**
 * Converts in process, through {@code html2adoc}.
 *
 * <p>Needs nothing installed, which is the whole point of it: an export becomes a JRE and the jar,
 * like publishing already is. It is a proof of concept and not yet the default - what it does
 * better than pandoc, and what it does worse, is written down in {@code docs/html2adoc-poc.adoc}.</p>
 */
public class NativeHtmlToAsciidoc implements HtmlToAsciidoc {

    /**
     * {@inheritDoc}
     *
     * <p>Always writes: the converter walks whatever jsoup parsed and has no failure to report,
     * so a page that it cannot do justice to comes out poorly rather than not at all.</p>
     */
    @Override
    public boolean convert(File html, File adoc) throws IOException {
        Document document = Jsoup.parse(Files.readString(html.toPath(), StandardCharsets.UTF_8));
        markVerbatimBlocks(document);
        Files.writeString(adoc.toPath(), Html2Adoc.convert(document.body()), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Says in HTML what {@link MacroTranslator} knows and the converter cannot see: the text of a
     * code macro's listing block is a code sample.
     *
     * <p>Without this the sample is escaped like prose, and the escapes are printed: AsciiDoc
     * substitutes nothing inside a {@code ----} block and strips no backslash there, so the
     * {@code ${HOME}} of a shell sample reaches the page as {@code $\{HOME}}. {@code <pre>} is the
     * HTML for "read this verbatim", so wrapping the block in one is enough for the converter to
     * stop escaping.</p>
     *
     * <p>Only on this path. The pandoc path must keep seeing the HTML it has always seen - pandoc
     * escapes a sample either way, and reads {@code <pre>} as a block of its own, which would add
     * a second set of delimiters inside the one the macro already wrote.</p>
     */
    private static void markVerbatimBlocks(Document document) {
        for (Element wrapper : document.select("div." + MacroTranslator.CODE_WRAPPER_CLASS)) {
            String listing = wrapper.wholeText();
            wrapper.empty();
            wrapper.appendChild(new Element("pre").appendChild(new TextNode(listing)));
        }
    }
}
