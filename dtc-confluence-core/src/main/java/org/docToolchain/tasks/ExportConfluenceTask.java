package org.docToolchain.tasks;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.util.ConfigObject;
import org.docToolchain.atlassian.confluence.clients.RestClient;
import org.docToolchain.atlassian.confluence.export.AttachmentDownloader;
import org.docToolchain.atlassian.confluence.export.ConfluenceConverter;
import org.docToolchain.atlassian.confluence.export.ConfluenceReader;
import org.docToolchain.atlassian.confluence.export.ExportedTree;
import org.docToolchain.atlassian.confluence.export.PageNaming;
import org.docToolchain.atlassian.confluence.export.PageTreeWalker;

/**
 * Exports a Confluence page and everything below it to AsciiDoc.
 *
 * <p>This is the driver: it reads the configuration, walks the tree, fetches the attachments and
 * hands each page to the converter. Every piece it uses was usable on its own before - and that is
 * exactly why none of them had ever run together.</p>
 *
 * <p>What is written, below {@code confluence.export.destDir}:</p>
 * <ul>
 *   <li>{@code docs/} - one AsciiDoc file per page, in folders mirroring the page tree</li>
 *   <li>{@code docs/images/} - the attachments, named {@code <version>_<file>}</li>
 *   <li>{@code docs/_menu.adoc} and {@code docs/_config.adoc} - what the microsite reads</li>
 * </ul>
 *
 * <p>Needs pandoc on the PATH; the publisher does not.</p>
 */
public class ExportConfluenceTask extends AbstractConfluenceTask {

    /** What Confluence answers a listing with where nothing says otherwise. */
    private static final int DEFAULT_PAGE_LIMIT = 100;

    /** Keeps the bullets of an exported list where a reader expects them. */
    private static final String CONFIG_ADOC = """
            ++++
            <style>
            div.ulist ul {
            margin-left: 1em !important;
            }
            </style>
            ++++

            """;

    private final String docDir;

    private ConfluenceReader reader;
    private ConfluenceConverter converter = new ConfluenceConverter();
    private AttachmentDownloader downloader;

    public ExportConfluenceTask(ConfigObject config, String docDir) {
        super(config);
        this.docDir = docDir;
        this.reader = new ConfluenceReader(configService, new RestClient(configService), pageLimit());
        this.downloader = new AttachmentDownloader(reader);
    }

    /** The shape the CLI builds every task through. */
    @SuppressWarnings("PMD.MethodNamingConventions")
    public static ExportConfluenceTask From(ConfigObject config, String docDir) {
        return new ExportConfluenceTask(config, docDir);
    }

    /** Visible for testing: puts a reader that answers from fixtures in place of the real one. */
    public void useReader(ConfluenceReader reader) {
        this.reader = reader;
        this.downloader = new AttachmentDownloader(reader);
    }

    /** Visible for testing. */
    public void useConverter(ConfluenceConverter converter) {
        this.converter = converter;
    }

    @Override
    public void execute() {
        File destDir = destinationDirectory();
        String rootPageId = rootPageId();
        converter.setStripChapterNumbering(flag("stripChapterNumbering", true));

        System.out.println("exporting page " + rootPageId + " and everything below it");
        System.out.println("destination:           " + destDir.getAbsolutePath());
        ExportedTree tree = new PageTreeWalker(reader, text("stripPagePrefixRegex")).walk(rootPageId);
        System.out.println("pages found:           " + tree.getPages().size());
        if (!tree.getUnreadable().isEmpty()) {
            System.out.println(">>> WARN: not readable:  " + tree.getUnreadable());
        }

        File docsDir = new File(destDir, "docs");
        if (!docsDir.exists() && !docsDir.mkdirs()) {
            throw new UncheckedIOException(new IOException("cannot create " + docsDir));
        }

        if (flag("downloadAttachments", true) && !tree.getAttachments().isEmpty()) {
            List<String> failed = downloader.downloadAll(tree, docsDir);
            System.out.println("attachments:           "
                    + (tree.getAttachments().size() - failed.size()) + " of "
                    + tree.getAttachments().size());
        }

        keepNamesApart(tree);
        Set<String> unknownTags = writePages(tree, docsDir);
        writeMenu(tree, docsDir);
        report(tree, unknownTags);
    }

    /**
     * Gives a page whose file name another page already took a name of its own.
     *
     * <p>A title becomes a file name by replacing everything a path stumbles over, so "A B" and
     * "A-B" both become "A_B" - and the second page would be written over the first while the
     * menu went on naming both. The page id is appended instead, which is ugly and unique.</p>
     *
     * <p>Before the attachments are fetched, so that the folders they are written into are the
     * ones the documents refer to.</p>
     */
    private static void keepNamesApart(ExportedTree tree) {
        Map<String, String> takenBy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> page : tree.getPages().entrySet()) {
            String pageId = page.getKey();
            String path = String.join("/", PageNaming.adocFolderStructure(tree.getPages(), pageId))
                    + "/" + nameOf(page.getValue());
            String first = takenBy.putIfAbsent(path, pageId);
            if (first == null) {
                continue;
            }
            String distinct = nameOf(page.getValue()) + "_" + pageId;
            System.out.println(">>> WARN: '" + page.getValue().get("title") + "' and the page of "
                    + "id " + first + " both want to be written as " + path
                    + "; this one becomes " + distinct);
            page.getValue().put("adocFilename", distinct);
        }
    }

    private static String nameOf(Map<String, Object> page) {
        Object adocFilename = page.get("adocFilename");
        Object filename = page.get("filename");
        return String.valueOf(adocFilename == null || String.valueOf(adocFilename).isEmpty()
                ? filename : adocFilename);
    }

    private Set<String> writePages(ExportedTree tree, File docsDir) {
        Set<String> unknownTags = new LinkedHashSet<>();
        for (String pageId : tree.getPages().keySet()) {
            List<String> childIds = tree.getChildrenByParent().getOrDefault(pageId, List.of());
            unknownTags.addAll(converter.writePage(pageId,
                    tree.getBodies().getOrDefault(pageId, ""),
                    childIds,
                    tree.getPages(),
                    tree.getAttachments(),
                    tree.getSpace(),
                    Map.of(),
                    docsDir));
        }
        return unknownTags;
    }

    private void writeMenu(ExportedTree tree, File docsDir) {
        try {
            Files.writeString(new File(docsDir, "_config.adoc").toPath(), CONFIG_ADOC,
                    StandardCharsets.UTF_8);
            // From "0", which is what the walk records as the parent of the root it started at.
            Files.writeString(new File(docsDir, "_menu.adoc").toPath(),
                    converter.createMenu(tree.getPages(), "0"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the menu", e);
        }
    }

    /** Says which tags had no translation, most frequent first, so a gap is visible rather than quiet. */
    private void report(ExportedTree tree, Set<String> unknownTags) {
        System.out.println();
        System.out.println("pages converted:       " + tree.getPages().size());
        if (unknownTags.isEmpty()) {
            System.out.println("unknown tags:          none");
            return;
        }
        System.out.println("unknown tags:          " + unknownTags);
        List<Map.Entry<String, List<String>>> byFrequency =
                new ArrayList<>(converter.getUnknownTagsStats().entrySet());
        byFrequency.sort(Comparator.comparingInt((Map.Entry<String, List<String>> e) ->
                e.getValue().size()).reversed());
        for (Map.Entry<String, List<String>> tag : byFrequency) {
            System.out.printf("%5d : %s%n", tag.getValue().size(), tag.getKey());
            for (String pageId : tag.getValue()) {
                Map<String, Object> page = tree.getPages().get(pageId);
                System.out.println("          - " + (page == null ? pageId : page.get("title")));
            }
        }
    }

    /**
     * @return where to write, as an absolute directory
     * @throws IllegalStateException where nothing says where to write
     */
    private File destinationDirectory() {
        String destDir = text("destDir");
        if (destDir.isEmpty()) {
            throw new IllegalStateException(
                    "confluence.export.destDir is not set; it says where the export is written");
        }
        File asGiven = new File(destDir);
        return asGiven.isAbsolute() ? asGiven : new File(docDir, destDir);
    }

    /**
     * @return the id of the page to start at, resolved from the title where only that is given
     * @throws IllegalStateException where neither is given, or the title names no page
     */
    private String rootPageId() {
        String rootPageId = text("rootPageId");
        if (!rootPageId.isEmpty()) {
            return rootPageId;
        }
        String rootPageTitle = text("rootPageTitle");
        if (rootPageTitle.isEmpty()) {
            throw new IllegalStateException("neither confluence.export.rootPageId nor "
                    + "confluence.export.rootPageTitle is set; one of them says where to start");
        }
        String spaceKey = spaceKey();
        Object found = confluenceClient.retrievePageIdByName(rootPageTitle, spaceKey);
        if (found == null) {
            throw new IllegalStateException("no page titled '" + rootPageTitle + "' in space "
                    + spaceKey);
        }
        System.out.println("root page '" + rootPageTitle + "' is " + found);
        return String.valueOf(found);
    }

    private String spaceKey() {
        String spaceKey = text("spaceKey");
        return spaceKey.isEmpty()
                ? String.valueOf(configService.getConfigProperty("confluence.spaceKey"))
                : spaceKey;
    }

    private int pageLimit() {
        Object configured = configService.getRawConfigProperty("confluence.export.pageLimit");
        if (configured instanceof Number number) {
            return number.intValue();
        }
        return configured == null
                ? DEFAULT_PAGE_LIMIT : Integer.parseInt(String.valueOf(configured));
    }

    /**
     * @return the switch below {@code confluence.export}, read without Groovy truth so that an
     *         explicit {@code false} is not mistaken for an absent setting
     */
    private boolean flag(String key, boolean whereUnset) {
        Object configured = configService.getRawConfigProperty("confluence.export." + key);
        if (configured == null) {
            return whereUnset;
        }
        return configured instanceof Boolean set
                ? set : Boolean.parseBoolean(String.valueOf(configured));
    }

    private String text(String key) {
        Object configured = configService.getConfigProperty("confluence.export." + key);
        return configured == null ? "" : String.valueOf(configured);
    }
}
