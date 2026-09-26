package org.docToolchain.atlassian.confluence.export;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * Turns the {@code ac:} and {@code ri:} markup of the Confluence storage format into the HTML
 * pandoc reads, and into the placeholders the AsciiDoc it writes is made from.
 *
 * <p>A macro that has no counterpart in AsciiDoc leaves a placeholder rather than its final
 * spelling, because pandoc escapes the punctuation AsciiDoc is built from: it would write
 * {@code ++[++NOTE++]++} where the document needs {@code [NOTE]}. The placeholders are put back
 * after pandoc has run, in {@link AdocOutput}.</p>
 *
 * <p>One instance translates one page: it carries the page it is translating and reports the tags
 * it did not recognise.</p>
 */
class MacroTranslator {

    /** What an admonition macro is called here, and what it is called in AsciiDoc. */
    private static final Map<String, String> ADMONITIONS = Map.of(
            "info", "NOTE",
            "warning", "WARNING",
            "note", "CAUTION",
            "tip", "TIP");

    private final String pageId;
    private final Map<String, Map<String, Object>> pages;
    private final Map<String, Map<String, Object>> attachments;
    private final Map<String, Map<String, Object>> users;
    private final boolean lucidChartsIframe;
    private final File lucidInfoFile;
    private final Map<String, List<String>> unknownTagsStats;

    /** The tags of this page the translation did not recognise, each named once. */
    private final Set<String> unknownTags = new LinkedHashSet<>();

    MacroTranslator(String pageId,
                    Map<String, Map<String, Object>> pages,
                    Map<String, Map<String, Object>> attachments,
                    Map<String, Map<String, Object>> users,
                    boolean lucidChartsIframe,
                    File lucidInfoFile,
                    Map<String, List<String>> unknownTagsStats) {
        this.pageId = pageId;
        this.pages = pages;
        this.attachments = attachments;
        this.users = users;
        this.lucidChartsIframe = lucidChartsIframe;
        this.lucidInfoFile = lucidInfoFile;
        this.unknownTagsStats = unknownTagsStats;
    }

    /**
     * Translates every {@code ac:} element of this document in place.
     *
     * @return the names of the tags that have no translation here
     */
    List<String> translate(Document dom) {
        Set<String> acTags = new LinkedHashSet<>();
        for (Element element : dom.select("*")) {
            if (element.tagName().startsWith("ac:")) {
                // The selector spells a namespace with a pipe, the tag name with a colon.
                acTags.add(element.tagName().replace(":", "|"));
            }
        }
        if (acTags.isEmpty()) {
            return List.of();
        }
        for (Element element : dom.select(String.join(", ", acTags))) {
            translateOne(element);
        }
        return new ArrayList<>(unknownTags);
    }

    private void translateOne(Element element) {
        switch (element.tagName()) {
            case "ac:image" -> image(element);
            case "ac:link" -> link(element);
            case "ac:inline-comment-marker" ->
                // A comment marker wraps text without marking it up. Keep the text, drop the tag.
                    element.unwrap();
            case "ac:layout", "ac:layout-section", "ac:layout-cell" ->
                // Multi-column layouts have no counterpart. Keep the children, drop the wrapper,
                // so that pandoc does not see a tag it knows nothing about.
                    element.unwrap();
            case "ac:placeholder" -> element.remove();
            case "ac:structured-macro" -> structuredMacro(element);
            case "ac:plain-text-link-body", "ac:parameter", "ac:rich-text-body",
                 "ac:plain-text-body" -> {
                // Part of the element that carries them, and handled there.
            }
            default -> report(element.tagName());
        }
    }

    private void structuredMacro(Element element) {
        String macroName = element.attr("ac:name");
        switch (macroName) {
            case "drawio" -> drawio(element);
            case "captioneditem" -> captionedItem(element);
            case "details", "table-filter" ->
                // `details` wraps a table of page metadata, `table-filter` a table with filter
                // controls. Both tables convert well on their own, so only the wrapper goes -
                // together with its parameters, which would otherwise leak into the text.
                    unwrapBody(element);
            case "status" -> status(element);
            case "contributors" -> contributors(element);
            case "detailssummary", "excerpt-include", "panel", "expandable-comment" ->
                // A listing built from a query, and macros with nothing to convert into. They go
                // with their children, so that no parameter of theirs is left as text. `expand`
                // is not among them: its body is content and is kept.
                    element.remove();
            case "view-file" -> viewFile(element);
            case "attachments" -> {
                // The attachments of this page are known to the caller, not here.
                element.before("%%attachments%%");
                element.remove();
            }
            case "profile" -> profile(element);
            case "lucidchart" -> lucidChart(element);
            case "toc" ->
                // AsciiDoc builds its own table of contents.
                    element.remove();
            case "children" -> children(element);
            case "tip", "info", "warning", "note" -> admonition(element, macroName);
            case "expand" -> expand(element);
            case "anchor" -> anchor(element);
            case "code", "paste-code-macro" -> code(element);
            default -> report("ac:structured-macro " + macroName);
        }
    }

    private void image(Element element) {
        String alignment = element.attr("ac:align");
        String width = element.attr("ac:width");
        String riFilename = element.select("ri|attachment").attr("ri:filename");
        String riVersion = element.select("ri|attachment").attr("ri:version-at-save");
        // Through the attachment map, so that a drawio diagram merged into a single file - the
        // name on disk is "<base>.drawio.png" while the storage format still says "<base>.png" -
        // is referred to under the name it was written as.
        Map<String, Object> attachment = attachmentNamed(riFilename);
        String filename = attachment == null
                ? riFilename
                : firstOf(text(attachment.get("filename")), riFilename);
        String version = attachment == null
                ? firstOf(riVersion, "1")
                : firstOf(text(attachment.get("version")), riVersion, "1");
        element.before("<img src='" + imageTarget(version + "_" + fileName(filename))
                + "' align='" + alignment + "' width='" + width + "' />");
        element.remove();
    }

    private void link(Element element) {
        if (element.children().isEmpty()) {
            return;
        }
        String anchor = element.attr("ac:anchor");
        String title = element.select("ri|page").attr("ri:content-title");
        String linkText = element.select("ac|plain-text-link-body").text();
        if (linkText.trim().isEmpty()) {
            linkText = element.select("ac|link-body").text();
        }
        String targetId = idOfPageTitled(title);
        if (targetId == null) {
            // A link to an attachment rather than to a page, or to a page outside this export.
            return;
        }
        // The AsciiDoc names, the same ones writePage writes under: with a page prefix stripped
        // the two differ, and a link built from the original name points at a file that was never
        // written. The folders are the AsciiDoc ones for the same reason.
        List<String> targetFolders = folders(targetId, "adocFilename");
        String name = firstOf(text(pages.get(targetId).get("adocFilename")),
                text(pages.get(targetId).get("filename")));
        String link = "../".repeat(folders(pageId, "adocFilename").size())
                + joinPath(String.join("/", targetFolders), name + ".adoc");
        if (!anchor.isEmpty()) {
            // Every anchor id is written with a leading underscore (see AdocOutput), because
            // AsciiDoc and HTML want an id to start with a letter or an underscore. A reference
            // to one has to be spelled the same way.
            link += "#_" + anchor;
        }
        element.before(" xref:" + link + "[" + linkText + "] ");
        element.remove();
    }

    private void drawio(Element element) {
        // The macro is rendered by the Confluence plugin when a page is shown; the storage format
        // carries only its parameters. The PNG that Confluence keeps beside it is the attachment
        // named "<diagramName>.png", and that is what the document refers to.
        String diagramName = element.select("ac|parameter[ac:name=diagramName]").text();
        String diagramWidth = element.select("ac|parameter[ac:name=diagramWidth]").text();
        if (!diagramName.isEmpty()) {
            String png = diagramName + ".png";
            Map<String, Object> attachment = attachmentNamed(png);
            if (attachment != null) {
                String version = firstOf(text(attachment.get("version")), "1");
                String filename = firstOf(text(attachment.get("filename")), png);
                String width = diagramWidth.isEmpty() ? "" : " width='" + diagramWidth + "'";
                element.before("<img src='" + imageTarget(version + "_" + fileName(filename))
                        + "'" + width + " />");
            }
        }
        element.remove();
    }

    private void captionedItem(Element element) {
        // Wraps an image or a diagram, with an anchor and a caption. The body is kept; the anchor
        // becomes an AsciiDoc block anchor through a placeholder, because pandoc would write
        // "[[name]]" as "++[[++name++]]++".
        String anchor = directParameter(element, "anchor");
        if (!anchor.isEmpty()) {
            element.before("\n<p>%%ANCHOR%%" + anchor + "%%ANCHOR-END%%</p>\n");
        }
        unwrapBody(element);
    }

    private void status(Element element) {
        // An inline coloured badge. AsciiDoc has no such thing, so it becomes an inline role that
        // a theme can style. No <span> around it: pandoc drops a raw span inside a table, and the
        // placeholder with it.
        String colour = firstOf(directParameter(element, "colour"), "Grey");
        String title = directParameter(element, "title");
        if (!title.isEmpty()) {
            element.before("%%STATUS-BEGIN-" + colour + "%%" + title + "%%STATUS-END%%");
        }
        element.remove();
    }

    private void contributors(Element element) {
        Object names = page(pageId).get("contributors");
        if (names instanceof List<?> contributors && !contributors.isEmpty()) {
            List<String> asText = contributors.stream().map(MacroTranslator::text).toList();
            element.before("<span>" + String.join(", ", asText) + "</span>");
        }
        element.remove();
    }

    private void viewFile(Element element) {
        String filename = element.select("ri|attachment").attr("ri:filename");
        String version = element.select("ri|attachment").attr("ri:version-at-save");
        // .text(), or the whole parameter element would land in the attribute.
        String height = firstOf(element.select("ac|parameter[ac:name=height]").text(), "400");
        List<String> folders = folders(pageId, "filename");
        String path = "../".repeat(folders.size()) + "images/" + String.join("/", folders);
        String file = path + "/" + firstOf(version, "1") + "_" + fileName(filename);
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            element.before("\n<div>\n++++%%CRLF%%\n"
                    + "&lt;iframe name=\"" + fileName(filename) + "\" allowfullscreen"
                    + " frameborder=\"0\" src='" + file + "' width='100%' height='" + height
                    + "' >&lt;/iframe>%%CRLF%%\n++++%%CRLF%%\n</div>\n");
        } else if (name.endsWith(".jpg") || name.endsWith(".png")) {
            // Through {filepath} like any other image: AsciiDoc resolves an image target against
            // imagesdir, which the file header already points at the images directory. The path
            // built above would be resolved a second time, to images/images/...
            element.before("<img src='"
                    + imageTarget(firstOf(version, "1") + "_" + fileName(filename)) + "'  />");
        } else {
            element.before("<a href='" + file + "'  >" + filename + "</a>");
        }
        element.remove();
    }

    private void profile(Element element) {
        String userkey = element.select("ri|user").attr("ri:userkey");
        Map<String, Object> user = users.get(userkey);
        if (user != null) {
            element.before("\nUser:: " + text(user.get("name")) + "%%CRLF%%\n"
                    + "// " + text(user.get("atlassianAccountId")) + "%%CRLF%%\n");
        } else {
            // Reported, because the page loses content here: the export carries no directory of
            // users, so a profile can only be named by the key Confluence refers to it with. A
            // silent removal is how a round trip loses things.
            report("ac:structured-macro profile (no user for " + userkey + ")");
            element.before("\nUser:: " + (userkey.isEmpty() ? "unknown" : userkey) + "%%CRLF%%\n");
        }
        // The macro itself has nothing more to say, and leaving it in the document would spill
        // its parameters into the text.
        element.remove();
    }

    private void lucidChart(Element element) {
        String documentId = element.select("ac|parameter[ac:name=documentId]").text();
        StringBuilder infos = new StringBuilder("\n// lucidChart\n"
                + "// localId: " + element.attr("ac:local-id") + "\n"
                + "// macroId: " + element.attr("ac:macro-id") + "\n");
        for (Element parameter : element.select("ac|parameter")) {
            infos.append("// ").append(parameter.attr("ac:name"))
                    .append(": ").append(parameter.text()).append("\n");
        }
        String chart;
        if (lucidChartsIframe) {
            chart = "\n++++%%CRLF%%\n&lt;iframe allowfullscreen frameborder=\"0\""
                    + " style=\"width:640px; height:480px\""
                    + " src=\"https://lucid.app/documents/embedded/" + documentId
                    + "\" >&lt;/iframe>%%CRLF%%\n++++%%CRLF%%\n";
        } else {
            String folders = String.join("/", folders(pageId, "filename"));
            chart = "\n%%CRLF%%\nimage::" + folders + "/" + documentId + ".png[]%%CRLF%%\n%%CRLF%%\n";
            noteLucidChart("images/" + folders + "/" + documentId + ".png\n");
        }
        element.before("\n    <div class=\"lucidchart-wrapper\">\n"
                + infos.toString().replace("\n", "%%CRLF%%") + "\n"
                + "    " + chart + "\n"
                + "    https://lucid.app/lucidchart/" + documentId + "/edit[edit lucidchart]\n"
                + "    </div>\n    ");
        element.remove();
    }

    private void children(Element element) {
        // Confluence's page footer is <hr/><p><children/></p>. The macro goes, and with it the
        // paragraph it stood alone in and the rule above it, so that no stray line is left.
        Element parent = element.parent();
        element.remove();
        if (parent != null && "p".equals(parent.tagName())
                && parent.text().trim().isEmpty() && parent.children().isEmpty()) {
            Element previous = parent.previousElementSibling();
            if (previous != null && "hr".equals(previous.tagName())) {
                previous.remove();
            }
            parent.remove();
        }
    }

    private void admonition(Element element, String macroName) {
        // An admonition needs "[NOTE]" and "====" as lines of their own in the AsciiDoc, and
        // pandoc escapes a literal bracket in text. Hence placeholders, put back after pandoc.
        // The body is moved in the DOM rather than rewritten as a string: a string would detach
        // any macro nested inside it, and the iteration would never reach it.
        String title = directParameter(element, "title");
        String type = ADMONITIONS.get(macroName);
        String titlePart = title.isEmpty()
                ? ""
                : "\n<p>%%ADMON-TITLE%%" + title.trim() + "%%ADMON-TITLE-END%%</p>\n";
        element.before(titlePart + "<p>%%ADMON-BEGIN-" + type + "%%</p>\n");
        element.after("\n<p>%%ADMON-END%%</p>");
        Element body = element.selectFirst("ac|rich-text-body");
        if (body != null) {
            for (Element heading : body.select("h1,h2,h3,h4,h5,h6")) {
                heading.before("<p>%%DISCRETE%%</p>");
            }
        }
        unwrapBody(element);
    }

    private void expand(Element element) {
        // arc42 templates use `expand` for a collapsible section. Same treatment as an admonition.
        String title = directParameter(element, "title");
        String titlePart = title.isEmpty()
                ? ""
                : "\n<p>%%EXPAND-TITLE%%" + title.trim() + "%%EXPAND-TITLE-END%%</p>\n";
        element.before(titlePart + "<p>%%EXPAND-BEGIN%%</p>\n");
        element.after("\n<p>%%EXPAND-END%%</p>");
        unwrapBody(element);
    }

    private void anchor(Element element) {
        String anchor = element.select("ac|parameter").text();
        if (!anchor.isEmpty()) {
            element.before("\n<p>%%ANCHOR%%" + anchor + "%%ANCHOR-END%%</p>\n");
        }
        element.remove();
    }

    private void code(Element element) {
        String language = element.select("ac|parameter[ac:name=language]").text();
        Element body = element.selectFirst("ac|plain-text-body");
        // Escaped, because it is put back into the document as markup: a sample containing an
        // <af:button/> was parsed as markup once, and disappeared from the exported document.
        //
        // wholeText rather than text: for the CDATA bodies Confluence writes the two are the
        // same, but a body without one would lose its line breaks.
        String code = Entities.escape(body == null ? "" : body.wholeText());
        // The attribute line travels as a placeholder for the same reason an admonition does:
        // pandoc escapes a literal bracket in text, and "++[++source, groovy++]++" is not a
        // source block, it is four plus signs and a sentence.
        element.html("\n    <div class=\"code-wrapper\">\n"
                + "    %%SOURCE-BEGIN%%" + language + "%%SOURCE-END%%%%CRLF%%\n"
                + "    ----%%CRLF%%\n"
                + "    " + code.replace("\n", "%%CRLF%%") + "%%CRLF%%\n"
                + "    ----%%CRLF%%\n"
                + "    </div>\n    ");
        element.unwrap();
    }

    /**
     * Keeps what a macro wraps and drops the macro: its body is unwrapped in place, and its own
     * parameters are removed.
     *
     * <p>Only direct children are touched. A macro nested in this one has parameters of its own,
     * and removing those before its handler runs would take its title or its language with it.</p>
     */
    private void unwrapBody(Element element) {
        Element body = element.selectFirst("ac|rich-text-body");
        if (body != null) {
            body.unwrap();
        }
        for (Element child : element.children()) {
            if ("ac:parameter".equals(child.tagName())) {
                child.remove();
            }
        }
        element.unwrap();
    }

    /**
     * @return the text of this macro's own parameter, empty where it has none. Direct children
     *         only, so that a nested macro's parameter of the same name is not read instead.
     */
    private static String directParameter(Element element, String name) {
        for (Element child : element.children()) {
            if ("ac:parameter".equals(child.tagName()) && name.equals(child.attr("ac:name"))) {
                return child.text();
            }
        }
        return "";
    }

    private void report(String tagName) {
        unknownTags.add(tagName);
        unknownTagsStats.computeIfAbsent(tagName, name -> new ArrayList<>()).add(pageId);
    }

    private void noteLucidChart(String line) {
        if (lucidInfoFile == null) {
            return;
        }
        try {
            Files.writeString(lucidInfoFile.toPath(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println(">>> WARN: could not note the LucidChart reference: "
                    + e.getMessage());
        }
    }

    private Map<String, Object> attachmentNamed(String filename) {
        if (filename.isEmpty()) {
            // An image may name something other than an attachment - a URL, for one. Matching an
            // empty name would pick whichever attachment happens to lack a name of its own.
            return null;
        }
        for (Map<String, Object> attachment : attachments.values()) {
            if (!pageId.equals(text(attachment.get("pageId")))) {
                continue;
            }
            // originalFilename is the name Confluence stored; filename may since have been
            // rewritten, by the drawio merge for one.
            if (filename.equals(text(attachment.get("originalFilename")))
                    || filename.equals(text(attachment.get("filename")))) {
                return attachment;
            }
        }
        return null;
    }

    private String idOfPageTitled(String title) {
        for (Map.Entry<String, Map<String, Object>> page : pages.entrySet()) {
            if (title.equals(text(page.getValue().get("title")))) {
                return page.getKey();
            }
        }
        return null;
    }

    private List<String> folders(String id, String nameKey) {
        return "filename".equals(nameKey)
                ? PageNaming.folderStructure(pages, id)
                : PageNaming.adocFolderStructure(pages, id);
    }

    private Map<String, Object> page(String id) {
        Map<String, Object> page = pages.get(id);
        return page == null ? new LinkedHashMap<>() : page;
    }

    /**
     * @return where an image of this page is, as AsciiDoc reads it: relative to {@code imagesdir},
     *         which the file header points at the images directory, and below the folders of the
     *         page where it has any. A root page has none, and naming them would leave a leading
     *         slash - an absolute path to nothing.
     */
    private String imageTarget(String fileName) {
        return joinPath(folders(pageId, "filename").isEmpty() ? "" : "{filepath}", fileName);
    }

    /** @return the segments as a path, skipping the ones that are not there */
    private static String joinPath(String... segments) {
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if (path.length() > 0) {
                path.append("/");
            }
            path.append(segment);
        }
        return path.toString();
    }

    /** @return the attachment's name as the file it was written as is called */
    private static String fileName(String filename) {
        return PageNaming.sanitizeAttachmentName(filename);
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
