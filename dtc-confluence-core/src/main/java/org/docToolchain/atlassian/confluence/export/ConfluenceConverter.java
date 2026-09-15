package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * Turns Confluence storage format into AsciiDoc.
 *
 * <p>The conversion has two halves. This one prepares the XHTML: it repairs what the storage
 * format spells awkwardly, translates the Confluence macros ({@link MacroTranslator}), and reports
 * the tags it did not recognise - which is where a round trip loses things quietly. The other half
 * is pandoc, which writes the AsciiDoc, and {@link AdocOutput}, which spells out afterwards what
 * pandoc would have escaped.</p>
 *
 * <p>Moved in behaviour from docToolchain's scripts/confluenceConverter.groovy, where it was a set
 * of closures shared between two drivers through a Gradle binding, so that the conversion can be
 * called - and tested - without a build tool around it.</p>
 */
public class ConfluenceConverter {

    /** How deep a numeric segment is padded before names are compared in their natural order. */
    private static final int NATURAL_ORDER_WIDTH = 10;

    /** A link into a Confluence space, as the editor writes it: the space, then the page id. */
    private static final Pattern CONFLUENCE_PAGE_LINK =
            Pattern.compile("/spaces/([^/]+)/pages/([0-9]+)/");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** The tags the conversion did not recognise, each with the pages it met them on. */
    private final Map<String, List<String>> unknownTagsStats = new LinkedHashMap<>();

    /** Whether a LucidChart reference becomes an iframe rather than an image. */
    private boolean lucidChartsIframe;

    /** Where discovered LucidChart references are logged; null to log none. */
    private File lucidInfoFile;

    /**
     * Strips a hand-written chapter number from a heading ("5.2.4. Title" -> "Title").
     * AsciiDoc can number sections itself, so the number carried over from Confluence is noise.
     */
    private boolean stripChapterNumbering = true;

    /** What one page of storage format became, and what of it was not understood. */
    public record ConvertedBody(String html, List<String> unknownTags) {
    }

    /** The pages and attachments of a subtree, as an export of that subtree sees them. */
    public record Subtree(Map<String, Map<String, Object>> pages,
                          Map<String, Map<String, Object>> attachments) {
    }

    public Map<String, List<String>> getUnknownTagsStats() {
        return unknownTagsStats;
    }

    public boolean isLucidChartsIframe() {
        return lucidChartsIframe;
    }

    public void setLucidChartsIframe(boolean lucidChartsIframe) {
        this.lucidChartsIframe = lucidChartsIframe;
    }

    public File getLucidInfoFile() {
        return lucidInfoFile;
    }

    public void setLucidInfoFile(File lucidInfoFile) {
        this.lucidInfoFile = lucidInfoFile;
    }

    public boolean isStripChapterNumbering() {
        return stripChapterNumbering;
    }

    public void setStripChapterNumbering(boolean stripChapterNumbering) {
        this.stripChapterNumbering = stripChapterNumbering;
    }

    /**
     * @return the folders of this page, named after the original file names. These govern the
     *         XHTML paths, the image paths, and the {@code {filepath}} attribute.
     */
    public List<String> getFolderStructure(Map<?, ?> pages, String pageId) {
        return PageNaming.folderStructure(pages, pageId);
    }

    /**
     * @return the same folders, named after the prefix-stripped file names. These govern the
     *         AsciiDoc output paths, the include directives, and the menu. Where no prefix is
     *         configured the two are the same, and where one is, an image reference keeps working
     *         while the document paths get shorter.
     */
    public List<String> getAdocFolderStructure(Map<?, ?> pages, String pageId) {
        return PageNaming.adocFolderStructure(pages, pageId);
    }

    /**
     * @return the id of the one page with this title
     * @throws IllegalArgumentException where no page or more than one carries it
     */
    public String findRootIdByTitle(Map<String, Map<String, Object>> pages, String title) {
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> page : pages.entrySet()) {
            if (title.equals(text(page.getValue().get("title")))) {
                matches.add(page.getKey());
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No page found with title '" + title + "'");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Multiple pages found with title '" + title
                    + "' (ids: " + matches + "); use rootPageId instead");
        }
        return matches.get(0);
    }

    /**
     * Restricts an export to one page and everything below it.
     *
     * <p>The root of the subtree is left without a parent, so that it is the root of what is
     * written rather than a page somewhere in the middle of a tree.</p>
     */
    public Subtree filterToSubtree(Map<String, Map<String, Object>> pages,
                                   Map<String, Map<String, Object>> attachments,
                                   String rootPageId) {
        if (!pages.containsKey(rootPageId)) {
            throw new IllegalArgumentException(
                    "rootPageId '" + rootPageId + "' not found in export");
        }
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> page : pages.entrySet()) {
            String parentId = text(page.getValue().get("parentId"));
            if (!parentId.isEmpty()) {
                childrenByParent.computeIfAbsent(parentId, id -> new ArrayList<>())
                        .add(page.getKey());
            }
        }
        Set<String> descendants = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootPageId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!descendants.add(current)) {
                continue;
            }
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        Map<String, Map<String, Object>> filteredPages = new LinkedHashMap<>();
        pages.forEach((id, page) -> {
            if (descendants.contains(id)) {
                filteredPages.put(id, page);
            }
        });
        filteredPages.get(rootPageId).put("parentId", 0);
        Map<String, Map<String, Object>> filteredAttachments = new LinkedHashMap<>();
        attachments.forEach((id, attachment) -> {
            if (descendants.contains(text(attachment.get("pageId")))) {
                filteredAttachments.put(id, attachment);
            }
        });
        return new Subtree(filteredPages, filteredAttachments);
    }

    /**
     * @return a page title as a file name any file system accepts
     */
    public String sanitizeFilename(String title) {
        return PageNaming.sanitizeFilename(title);
    }

    /**
     * Prepares one page of storage format for pandoc.
     *
     * @return the XHTML pandoc reads, and the tags this conversion has no translation for
     */
    public ConvertedBody fixBody(String pageId,
                                 String body,
                                 Map<String, Map<String, Object>> users,
                                 Map<String, Map<String, Object>> pages,
                                 Map<String, Map<String, Object>> attachments,
                                 Map<String, Object> space) {
        String prepared = body
                // How a CDATA section is closed in the XHTML looks like an oversight.
                .replace("]] ><", "]]><")
                .replaceAll("<h[1-9]> </h[1-9]>", "")
                .replace("colspan=\"1\"", "")
                // A cell spanning two columns is one pandoc does not carry over; an empty cell
                // beside it says the same thing in a table it does understand.
                .replaceAll("colspan=\"2\"(.*?)</(t[dh])>", "$1</$2><$2></$2>");

        Document dom = Jsoup.parse(prepared, "utf-8", Parser.xmlParser());
        dom.outputSettings().prettyPrint(false);
        dom.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
        dom.outputSettings().charset(StandardCharsets.UTF_8);
        emptyBreakOnlyCells(dom);
        markRowHeaderTables(dom);
        rewriteLinksIntoThisExport(dom, pageId, pages, space);
        unwrapEditorSpans(dom);

        List<String> unknownTags = new MacroTranslator(pageId, pages, attachments, users,
                lucidChartsIframe, lucidInfoFile, unknownTagsStats).translate(dom);

        String html = dom.html()
                // Some last dirty hacks.
                .replace("<a ", " <a ")
                .replaceAll("[/][#][/]", "/\\\\#/")
                .replace("<p></p><strong><br />", "<strong>")
                .replace("<strong><br /></strong>", "<br />")
                .replaceAll("<div><div class=\"title\">([^<]+)</div></div>", ".$1")
                .replaceAll("<strong><br />[.]([^<]+)</strong>", ".$1");
        if (stripChapterNumbering) {
            html = html.replaceAll("(<h[1-9](?:\\s[^>]*)?>)\\s*\\d+(?:\\.\\d+)*\\.?\\s+", "$1");
        }
        return new ConvertedBody(html, unknownTags);
    }

    /**
     * Empties a cell whose only content is a line break.
     *
     * <p>Confluence writes {@code <td><br/></td>} for a field left blank, and pandoc writes the
     * break as " +", an AsciiDoc hard line break, which ends up as a stray "+" in the table. Only
     * cells whose children are all breaks and that carry no text: one holding an image, a
     * paragraph or a table of its own is left alone.</p>
     */
    private static void emptyBreakOnlyCells(Document dom) {
        for (Element cell : dom.select("td, th")) {
            Elements children = cell.children();
            boolean onlyBreaks = !children.isEmpty()
                    && children.stream().allMatch(child -> "br".equals(child.tagName()));
            if (onlyBreaks && cell.text().trim().isEmpty()) {
                cell.empty();
            }
        }
    }

    /**
     * Marks a table whose every row starts with a header cell - Confluence's Status/Author/Date
     * block is one - so that the AsciiDoc can ask for a header column.
     *
     * <p>A column of style "h" renders as a header, which is what the markup already says. The
     * alternative, wrapping every cell in {@code <strong>}, says it only in bold.</p>
     *
     * @see <a href="https://docs.asciidoctor.org/asciidoc/latest/tables/format-column-content/">
     *      AsciiDoc column content</a>
     */
    private static void markRowHeaderTables(Document dom) {
        for (Element table : dom.select("table")) {
            Element tbody = table.selectFirst("tbody");
            List<Element> rows = (tbody == null ? table.children() : tbody.children()).stream()
                    .filter(child -> "tr".equals(child.tagName()))
                    .toList();
            if (rows.isEmpty() || !rows.stream().allMatch(ConfluenceConverter::startsWithHeader)) {
                continue;
            }
            long columns = cellsOf(rows.get(0)).count();
            if (columns < 2) {
                continue;
            }
            // Read again once pandoc has run. Hyphens as separators, because pandoc escapes an
            // underscore.
            table.before("<p>%%TABLE-ROWHEADER-" + columns + "%%</p>");
            for (Element row : rows) {
                for (Element header : row.select("th")) {
                    header.tagName("td");
                    header.removeAttr("scope");
                }
            }
        }
    }

    private static boolean startsWithHeader(Element row) {
        return cellsOf(row).findFirst().map(cell -> "th".equals(cell.tagName())).orElse(false);
    }

    private static Stream<Element> cellsOf(Element row) {
        return row.children().stream()
                .filter(cell -> "td".equals(cell.tagName()) || "th".equals(cell.tagName()));
    }

    /**
     * Rewrites a link that still points at Confluence to the document the export writes for that
     * page.
     */
    private void rewriteLinksIntoThisExport(Document dom,
                                            String pageId,
                                            Map<String, Map<String, Object>> pages,
                                            Map<String, Object> space) {
        for (Element link : dom.select("a")) {
            Matcher matcher = CONFLUENCE_PAGE_LINK.matcher(link.attr("href"));
            if (!matcher.find()) {
                continue;
            }
            String targetSpace = matcher.group(1);
            String targetPage = matcher.group(2);
            if (matcher.find()) {
                // More than one page in one link is not a link this can read.
                continue;
            }
            if (!text(space.get("key")).equals(targetSpace)) {
                System.out.println("WARNING: can't rewrite links between different spaces (source: "
                        + text(space.get("key")) + ", target: " + targetSpace + ")");
                continue;
            }
            Map<String, Object> target = pages.get(targetPage);
            if (target == null || text(target.get("filename")).isEmpty()) {
                // A subtree export may hold a link to a page of the same space that is not part
                // of it. Rewriting that would point at ".../.html", a link to nothing; the URL
                // that works is the better answer.
                System.out.println("WARNING: link target " + targetPage
                        + " is not part of this export, leaving the URL as it is");
                continue;
            }
            // The AsciiDoc names, because those are what the pages were written under. With a
            // prefix stripped the two differ, and a link built from the original names would
            // point at a file that was never written.
            String name = firstOf(text(target.get("adocFilename")), text(target.get("filename")));
            String folders = String.join("/", getAdocFolderStructure(pages, targetPage));
            String prefix = "../".repeat(getAdocFolderStructure(pages, pageId).size());
            link.attr("href", prefix + folders + "/" + name + ".html");
        }
    }

    /** Drops the spans the Confluence editor leaves behind, keeping what they wrap. */
    private static void unwrapEditorSpans(Document dom) {
        for (Element span : dom.select("span")) {
            String styleClass = span.attr("class");
            if (styleClass.startsWith("css-")
                    || styleClass.startsWith("loader-wrapper")
                    || styleClass.contains("smart-link-title-wrapper")) {
                span.unwrap();
            }
        }
    }

    /**
     * Converts one page and writes it: the XHTML through {@link #fixBody}, pandoc over that, then
     * the placeholders, the file header, the includes of its children and its attachments.
     *
     * @return the tags this page carried that the conversion did not recognise
     */
    public List<String> writePage(String pageId,
                                  String rawBody,
                                  List<String> childIds,
                                  Map<String, Map<String, Object>> pages,
                                  Map<String, Map<String, Object>> attachments,
                                  Map<String, Object> space,
                                  Map<String, Map<String, Object>> users,
                                  File destDir) {
        Map<String, Object> metaData = pages.get(pageId);
        String adocFilename =
                firstOf(text(metaData.get("adocFilename")), text(metaData.get("filename")));
        // The original folders name the image paths and the {filepath} attribute; the
        // prefix-stripped ones name the documents, their includes and the menu.
        List<String> folderStructure = getFolderStructure(pages, pageId);
        List<String> adocFolderStructure = getAdocFolderStructure(pages, pageId);
        String deepFilename = String.join("/", adocFolderStructure) + "/" + adocFilename;

        if (!adocFolderStructure.isEmpty()) {
            new File(destDir, String.join("/", adocFolderStructure)).mkdirs();
        }
        File outFile = new File(destDir, deepFilename + ".html");
        ConvertedBody converted =
                fixBody(pageId, rawBody == null ? "" : rawBody, users, pages, attachments, space);
        try {
            Files.writeString(outFile.toPath(), converted.html(), StandardCharsets.UTF_8);
            File adocFile = new File(outFile.getCanonicalPath()
                    .substring(0, outFile.getCanonicalPath().length() - ".html".length()) + ".adoc");
            if (!toAsciidoc(outFile, adocFile)) {
                return converted.unknownTags();
            }
            System.out.println(deepFilename);
            System.out.println(text(metaData.get("title")));
            String adoc = AdocOutput.substitute(Files.readString(adocFile.toPath()));
            adoc = withAttachments(adoc, pageId, attachments, folderStructure);
            Files.writeString(adocFile.toPath(),
                    fileHeader(metaData, adocFilename, folderStructure, adocFolderStructure)
                            + "== " + text(metaData.get("title")) + "\n\n"
                            + adoc
                            + "\n\n"
                            + childIncludes(pageId, childIds, pages),
                    StandardCharsets.UTF_8);
            Files.delete(outFile.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + deepFilename, e);
        }
        return converted.unknownTags();
    }

    /**
     * Runs pandoc over the prepared XHTML.
     *
     * @return whether it wrote a document; a warning is printed where it did not
     */
    private static boolean toAsciidoc(File html, File adoc) throws IOException {
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

    /**
     * The attributes the microsite reads, and the paths the document resolves its images by.
     *
     * <p>Visible to its test: no line of it may be indented, and that is worth stating.</p>
     */
    static String fileHeader(Map<String, Object> metaData,
                                     String adocFilename,
                                     List<String> folderStructure,
                                     List<String> adocFolderStructure) {
        return "\n"
                + ":jbake-menu: "
                + (adocFolderStructure.isEmpty() ? "-" : adocFolderStructure.get(0)) + "\n"
                + ":jbake-deep-menu: " + String.join("/", adocFolderStructure) + "\n"
                + ":jbake-status: published\n"
                + ":jbake-type: page_custom_menu\n"
                + ":jbake-order: " + firstOf(text(metaData.get("position")), "0") + "\n"
                + ":jbake-root: " + "../".repeat(adocFolderStructure.size()) + "\n"
                + ":filename: " + adocFilename + ".adoc\n"
                + ":filepath: " + String.join("/", folderStructure) + "\n"
                + "include::{jbake-root}_config.adoc[]\n"
                + "ifdef::show-microsite-menu[]\n"
                + "include::{jbake-root}_menu.adoc[]\n"
                + "++++\n"
                + "<!-- endtoc -->\n"
                + "++++\n"
                + "endif::show-microsite-menu[]\n"
                + "ifndef::imagesdir[:imagesdir: {jbake-root}images]\n"
                + "\n";
    }

    /**
     * The includes of this page's children, in their natural order.
     *
     * <p>No line of the block is indented: AsciiDoc reads four spaces as a literal block, which
     * would swallow the includes and leave every child page out of the document.</p>
     */
    static String childIncludes(String pageId,
                                        List<String> childIds,
                                        Map<String, Map<String, Object>> pages) {
        String parentName = adocNameOf(pages.get(pageId));
        List<String> includes = new ArrayList<>();
        for (String childId : childIds) {
            Map<String, Object> child = pages.get(childId);
            if (child == null) {
                continue;
            }
            includes.add("include::" + parentName + "/" + adocNameOf(child)
                    + ".adoc[levelOffset=+1]");
        }
        if (includes.isEmpty()) {
            return "";
        }
        // In their natural order: the REST API returns children sorted by title, so "arc42_10"
        // comes before "arc42_2" and the positions taken from that order are wrong as well.
        includes.sort(Comparator.comparing(ConfluenceConverter::naturalKey));
        return "\nifdef::includeChildren[]\n"
                + String.join("\n", includes)
                + "\nendif::includeChildren[]\n";
    }

    /** Replaces the attachment marker, where the page carried one, with a list of its files. */
    private static String withAttachments(String adoc,
                                          String pageId,
                                          Map<String, Map<String, Object>> attachments,
                                          List<String> folderStructure) {
        if (!adoc.contains("%%attachments%%")) {
            return adoc;
        }
        StringBuilder list = new StringBuilder("\n.Attachments\n\n");
        String prefix = "../".repeat(folderStructure.size()) + "images/"
                + String.join("/", folderStructure) + "/";
        attachments.values().stream()
                .filter(attachment -> pageId.equals(text(attachment.get("pageId"))))
                .forEach(attachment -> {
                    String version = text(attachment.get("version"));
                    String filename = text(attachment.get("filename"));
                    list.append("* link:").append(prefix).append(version).append("_")
                            .append(PageNaming.sanitizeAttachmentName(filename))
                            .append("[").append(filename).append(" (v").append(version)
                            .append(")]\n");
                });
        return adoc.replace("%%attachments%%", list.toString());
    }

    /**
     * The menu of the microsite: every page below this one, as deep as it sits.
     *
     * @return one AsciiDoc list item per page, in the natural order of their titles
     */
    public String createMenu(Map<String, Map<String, Object>> pages, String startPageId) {
        StringBuilder menu = new StringBuilder();
        pages.entrySet().stream()
                .filter(page -> text(page.getValue().get("parentId")).equals(startPageId))
                .sorted(Comparator.comparing(page -> naturalKey(text(page.getValue().get("title")))))
                .forEach(page -> {
                    List<String> folders = PageNaming.adocFolderStructure(pages, page.getKey());
                    menu.append("*".repeat(folders.size() + 1))
                            .append(" xref:{jbake-root}").append(String.join("/", folders))
                            .append("/").append(adocNameOf(page.getValue())).append(".adoc[")
                            .append(text(page.getValue().get("title"))).append("]\n")
                            .append(createMenu(pages, page.getKey()));
                });
        return menu.toString();
    }

    private static String adocNameOf(Map<String, Object> page) {
        return firstOf(text(page.get("adocFilename")), text(page.get("filename")));
    }

    /**
     * @return the name with every run of digits padded, so that names sort the way a reader reads
     *         them: "chapter 2" before "chapter 10"
     */
    private static String naturalKey(String name) {
        StringBuilder key = new StringBuilder();
        Matcher digits = DIGITS.matcher(name);
        while (digits.find()) {
            digits.appendReplacement(key, "0".repeat(
                    Math.max(0, NATURAL_ORDER_WIDTH - digits.group().length())) + digits.group());
        }
        digits.appendTail(key);
        return key.toString();
    }

    private static String firstOf(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
