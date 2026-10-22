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
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.TextNode;

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
    /**
     * What an emoticon may be called for its name to become an AsciiDoc attribute name.
     *
     * <p>The rule belongs to {@link AdocOutput}, which reads the placeholder back: a name it
     * cannot carry - an underscore, which pandoc escapes - would leave the placeholder in the
     * page as text, and with nothing reported.</p>
     */
    private static final Pattern EMOTICON_NAME = Pattern.compile(AdocOutput.EMOTICON_NAME);

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
            case "ac:emoticon" -> emoticon(element);
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

    /**
     * Writes an emoticon as a reference to a document attribute named after it.
     *
     * <p>Until now this class had no case for {@code ac:emoticon}, so the element was reported and
     * left in the document, and whichever converter ran next decided its fate: pandoc dropped it,
     * and the native converter dropped it and swallowed the spaces on either side with it. In a
     * table that is not a lost ornament but a lost answer: on the Maven space's own
     * <em>Branch Protection</em> page every one of 127 ticks is the answer its row gives.</p>
     *
     * <p>An attribute rather than a fixed spelling, because no spelling is right for everyone: a
     * check mark reads well in a table and says nothing to a screen reader, and Confluence's own
     * {@code alt} text reads clumsily where a glyph would do. {@link AdocOutput} writes a default
     * for every emoticon the page uses, guarded by {@code ifndef::} - the idiom the page header
     * already uses for {@code imagesdir} - so a page renders on its own and one
     * {@code -a emoticon-tick=...} still overrides it everywhere.</p>
     *
     * <p>A placeholder rather than the attribute reference itself: pandoc escapes the brace and
     * would write {@code ++{++emoticon-tick}}.</p>
     */
    private void emoticon(Element element) {
        String name = element.attr("ac:name").toLowerCase(Locale.ROOT);
        if (!EMOTICON_NAME.matcher(name).matches()) {
            // Nothing to name an attribute after. Reported with the name, so that the summary
            // says which emoticon went rather than only that one did.
            report(element.tagName() + (name.isEmpty() ? "" : " " + name));
            element.remove();
            return;
        }
        // A text node rather than element.before(String), which re-parses what it is given: the
        // guard above leaves nothing to re-parse, and this keeps it that way if the guard widens.
        element.replaceWith(new TextNode("%%EMOTICON-" + name + "%%"));
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
            case "code", "paste-code-macro", "noformat" -> code(element);
            default -> report("ac:structured-macro " + macroName);
        }
    }

    private void image(Element element) {
        String alignment = element.attr("ac:align");
        String width = element.attr("ac:width");
        // An ac:image names either an attachment of the page or, with ri:url, an image somewhere
        // else. Only the attachment was read, so an embedded URL became "1_" - a reference to a
        // file that was never there. The publisher writes ri:url for any source starting with
        // http, so this is the other half of that round trip.
        String riUrl = element.select("ri|url").attr("ri:value");
        if (!riUrl.isEmpty()) {
            element.before(img(riUrl, alignment, width));
            element.remove();
            return;
        }
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
        element.before(img(imageTarget(version + "_" + fileName(filename)), alignment, width));
        element.remove();
    }

    /**
     * An img element, built rather than written out.
     *
     * <p>Written as a fragment, the values were parsed again as markup: jsoup decodes
     * {@code ri:value} before this runs, so a stored URL carrying {@code &apos;} closed the
     * {@code src} attribute, and everything after it was read as further attributes - a page
     * could add an {@code onerror} to the exported HTML. The same holds for an attachment whose
     * file name carries a quote. Set through the attribute API, a value is escaped when the
     * document is serialised and means itself.</p>
     */
    private static Element img(String source, String alignment, String width) {
        Element img = new Element("img");
        img.attr("src", source);
        img.attr("align", alignment);
        img.attr("width", width);
        return img;
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
            // Spelled by the same rule as the anchor it points at, underscore included.
            link += "#" + AdocOutput.anchorId(anchor);
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
        String file = attachmentPath(firstOf(version, "1") + "_" + fileName(filename));
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            element.before("\n<div>\n%%PASSTHROUGH-BEGIN%%%%CRLF%%\n"
                    + "&lt;iframe name=\"" + fileName(filename) + "\" allowfullscreen"
                    + " frameborder=\"0\" src='" + file + "' width='100%' height='" + height
                    + "' >&lt;/iframe>%%CRLF%%\n%%PASSTHROUGH-END%%%%CRLF%%\n</div>\n");
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
            chart = "\n%%PASSTHROUGH-BEGIN%%%%CRLF%%\n&lt;iframe allowfullscreen frameborder=\"0\""
                    + " style=\"width:640px; height:480px\""
                    + " src=\"https://lucid.app/documents/embedded/" + documentId
                    + "\" >&lt;/iframe>%%CRLF%%\n%%PASSTHROUGH-END%%%%CRLF%%\n";
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

    /**
     * Writes a macro that carries preformatted text as {@code <pre><code>}.
     *
     * <p>{@code noformat} is this macro without a language, and belongs here for that reason.
     * Untranslated its body did not survive: pandoc wrote it out as escaped prose with its
     * structure gone - a directory tree came back as {@code ++|++ {plus}- myproj} - and the
     * native converter dropped it entirely. On the Maven space that body is a directory tree, a
     * log excerpt or a stack trace. All 27 occurrences measured there carry one plain-text body
     * and no parameter at all, so a {@code noformat} never reaches the language below; a
     * {@code code} macro does.</p>
     */
    private void code(Element element) {
        String language = element.select("ac|parameter[ac:name=language]").text();
        Element body = element.selectFirst("ac|plain-text-body");
        // wholeText rather than text: for the CDATA bodies Confluence writes the two are the
        // same, but a body without one would lose its line breaks.
        String sample = body == null ? "" : body.wholeText();
        // Handed to pandoc as code rather than as text. Written as text it was reflowed like a
        // paragraph: a YAML sample lost the indentation that is its meaning, its brackets and
        // underscores came back escaped as "++[++", and "image: nginx:1.25" was promoted to a
        // block image. A <pre><code> is a code block to pandoc, which writes it out verbatim -
        // delimiters and "[source, yaml]" line included, so no placeholder is needed for either.
        //
        // Built rather than written out: interpolated into markup, a language parameter ending
        // the class attribute would put whatever follows it into the exported page as markup,
        // and a sample containing an <af:button/> was parsed as markup once and disappeared.
        // Set through the DOM, both are escaped when the document is serialised.
        Element code = new Element("code");
        if (!language.isEmpty()) {
            code.attr("class", "language-" + language);
        }
        code.text(sample);
        element.before(new Element("pre").appendChild(code));
        element.remove();
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
    /**
     * @return the file below the image directory, as a path from where this document sits - for
     *         what AsciiDoc does not resolve against imagesdir itself: a link, or the src of an
     *         iframe. The same folders the downloader writes to, by way of
     *         {@link PageNaming#attachmentFolders}.
     */
    private String attachmentPath(String fileName) {
        return "../".repeat(folders(pageId, "filename").size()) + "images/"
                + String.join("/", PageNaming.attachmentFolders(pages, pageId)) + "/" + fileName;
    }

    private String imageTarget(String fileName) {
        // {filepath} for the folders of the page - the document carries them as that attribute -
        // and then the page's own folder, which is what keeps two siblings' attachments apart.
        return joinPath(folders(pageId, "filename").isEmpty() ? "" : "{filepath}",
                PageNaming.ownFolder(pages, pageId), fileName);
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
