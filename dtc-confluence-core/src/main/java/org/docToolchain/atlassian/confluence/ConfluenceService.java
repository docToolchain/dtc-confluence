package org.docToolchain.atlassian.confluence;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.docToolchain.atlassian.confluence.clients.ConfluenceClient;
import org.docToolchain.configuration.ConfigService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;

/**
 * The operations around publishing that are not requests themselves: locating and reading the
 * generated HTML, and clearing a space.
 */
public class ConfluenceService {

    private static final String ASCIIDOC_SUFFIXES = ".*[.](ad|adoc|asciidoc)$";

    /** Confluence caps a page listing at this many entries per request. */
    private static final int WIPE_PAGE_LIMIT = 100;

    private final ConfigService configService;

    public ConfluenceService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Resolves a configured input file against the document directory.
     *
     * @throws IllegalStateException if the file is AsciiDoc rather than the HTML generated from it
     */
    public String checkAndBuildCanonicalFileName(String filename) {
        String canonicalFilePath = (configService.getConfigProperty("docDir") + "/" + filename.trim()).trim();
        System.out.println("publish " + filename);

        if (filename.matches(ASCIIDOC_SUFFIXES)) {
            System.out.println("HINT:");
            System.out.println("please first convert " + canonicalFilePath + " to html by executing generateHTML");
            System.out.println("the generated file will be found in "
                    + configService.getConfigProperty("outputPath")
                    + "/html5/. and has to be referenced instead of the .adoc file");
            throw new IllegalStateException("config problem");
        }
        return canonicalFilePath;
    }

    /**
     * Reads the generated HTML as XML, because Confluence storage format is XHTML and the
     * transformers rely on the document staying well formed.
     */
    public Document parseFile(File htmlFile) {
        Document dom = Jsoup.parse(read(htmlFile), "utf-8", Parser.xmlParser());
        // keep line breaks and spacing, so that code blocks survive
        dom.outputSettings().prettyPrint(false);
        // keep the document valid XHTML with respect to entities
        dom.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
        dom.outputSettings().charset(StandardCharsets.UTF_8);
        return dom;
    }

    /**
     * @return the comma-separated keywords from the document's meta tag, which become page labels
     */
    public List<String> getKeywords(Document dom) {
        List<String> keywords = new ArrayList<>();
        for (Element meta : dom.select("meta[name=keywords]")) {
            for (String keyword : meta.attr("content").split(",")) {
                keywords.add(keyword.trim());
            }
            System.out.println("Keywords:" + keywords);
        }
        return keywords;
    }

    public void wipeConfluenceSpace(ConfluenceClient confluenceClient) {
        String spaceKey = String.valueOf(configService.getConfigProperty("confluence.spaceKey"));
        Map<?, ?> allPages = confluenceClient.fetchPagesBySpaceKey(spaceKey, WIPE_PAGE_LIMIT);
        System.out.println("Deleting " + allPages.size() + " pages from space " + spaceKey);
        allPages.forEach((key, page) -> {
            Map<?, ?> entry = (Map<?, ?>) page;
            System.out.println("Deleting page: [" + entry.get("title") + "]");
            confluenceClient.deletePage(String.valueOf(entry.get("id")));
        });
        System.out.println("Successfully wiped " + spaceKey);
    }

    private static String read(File htmlFile) {
        try {
            return Files.readString(htmlFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + htmlFile, e);
        }
    }
}
