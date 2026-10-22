package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.docToolchain.html2adoc.Html2Adoc;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Converts in process, through {@code html2adoc}.
 *
 * <p>Needs nothing installed, which is the whole point of it: an export becomes a JRE and the jar,
 * like publishing already is. It is a proof of concept and not yet the default - what it does
 * better than pandoc, and what it does worse, is written down in {@code docs/html2adoc-poc.adoc}.</p>
 */
public class NativeHtmlToAsciidoc implements HtmlToAsciidoc {

    /**
     * The level the page's own headings are written at, one below the title.
     *
     * <p>{@link ConfluenceConverter#writePage} writes {@code "== " + title} above everything this
     * converter produces, which is level 1. A heading of the page body belongs under that, and
     * {@code <h1>} would otherwise become a level-0 section and outrank it.</p>
     */
    private static final int TOP_HEADING_LEVEL = 2;

    /**
     * {@inheritDoc}
     *
     * <p>Always writes: the converter walks whatever jsoup parsed and has no failure to report,
     * so a page that it cannot do justice to comes out poorly rather than not at all.</p>
     */
    @Override
    public boolean convert(File html, File adoc) throws IOException {
        Document document = Jsoup.parse(Files.readString(html.toPath(), StandardCharsets.UTF_8));
        Files.writeString(adoc.toPath(), Html2Adoc.convert(document.body(), TOP_HEADING_LEVEL),
                StandardCharsets.UTF_8);
        return true;
    }
}
