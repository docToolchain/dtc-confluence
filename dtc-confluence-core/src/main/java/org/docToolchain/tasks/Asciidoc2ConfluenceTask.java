package org.docToolchain.tasks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import groovy.util.ConfigObject;
import org.docToolchain.atlassian.confluence.ConfluenceService;
import org.docToolchain.atlassian.confluence.clients.ConfluenceApiVersion;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClient;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV1;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClientV2;
import org.docToolchain.atlassian.confluence.page.Page;
import org.docToolchain.atlassian.confluence.page.PageDecorator;
import org.docToolchain.atlassian.confluence.page.PageTree;
import org.docToolchain.atlassian.confluence.page.PageTreeBuilder;
import org.docToolchain.atlassian.confluence.publish.BodyBuilder;
import org.docToolchain.atlassian.confluence.publish.PublishSettings;
import org.docToolchain.atlassian.confluence.publish.PublishedBody;
import org.docToolchain.atlassian.confluence.publish.Upload;
import org.docToolchain.util.ContentHash;
import org.jsoup.nodes.Document;

/**
 * Publishes an Asciidoctor-generated HTML document to Confluence.
 *
 * <p>Based on the asciidoc2confluence script by Ralf D. Mueller and Alexander Heusingfeld,
 * https://github.com/rdmueller/asciidoc2confluence. The document is expected in Asciidoctor's
 * default shape, where a {@code div.sect1} is a top-level section and nests {@code div.sect2}
 * beneath it; how much of that nesting becomes separate Confluence pages is configured.</p>
 */
public class Asciidoc2ConfluenceTask extends DocToolchainTask {

    /** Where a page carries the hash of what produced it, so an unchanged page is not rewritten. */
    private static final Pattern REMOTE_HASH = Pattern.compile("(?ms)hash: #([^#]+)#");

    /** How many pages to ask Confluence for at a time while listing a space. */
    private static final int DEFAULT_PAGE_LIMIT = 100;

    /** Stands in for the id of a page that does not exist yet, so its children have a parent. */
    private static final String NOT_CREATED = "(not created)";

    private final ConfigObject config;
    private final String docDir;
    private final ConfluenceService confluenceService;

    private ConfluenceClient confluenceClient;
    private String baseUrl;
    /** The pages of a space, as they were listed for the first input that needed them. */
    private final Map<String, Map<?, ?>> allPagesBySpace = new LinkedHashMap<>();

    /**
     * Whether this run only says what it would do. Read from the configuration rather than passed
     * in, so that the CLI, a build plugin and a configuration file all reach it the same way.
     */
    private final boolean dryRun;

    /**
     * Whether a page of the right title in the wrong place is moved rather than refused. Only ever
     * a page this publisher wrote: moving somebody else's page because it happens to be called
     * "Motivation" is not a thing a documentation build should do unasked.
     */
    private final boolean moveExistingPages;

    /** What a dry run counts, so the run can end with a sentence rather than a wall of lines. */
    private final Map<Verdict, Integer> verdicts = new EnumMap<>(Verdict.class);

    /** The pages this run wrote or confirmed, so that what it did not touch can be named. */
    private final Set<String> written = new LinkedHashSet<>();

    private String confluenceSpaceKey;
    private String confluencePagePrefix = "";
    private String confluencePageSuffix = "";

    @SuppressWarnings("PMD.MethodNamingConventions")
    public static Asciidoc2ConfluenceTask From(ConfigObject config, String docDir) {
        return new Asciidoc2ConfluenceTask(config, docDir);
    }

    public Asciidoc2ConfluenceTask(ConfigObject config, String docDir) {
        super(config);
        this.config = config;
        this.docDir = docDir;
        this.confluenceService = new ConfluenceService(configService);
        // This task descends from DocToolchainTask rather than from AbstractConfluenceTask, so it
        // picks its own client - through the same derivation, so that an unset useV1Api does not
        // send Data Center to a v2 endpoint that answers 404.
        this.confluenceClient = ConfluenceApiVersion.useV1(configService)
                ? new ConfluenceClientV1(configService)
                : new ConfluenceClientV2(configService);
        // Raw, because "dryRun = false" is a decision and Groovy truth would read it as absence.
        this.dryRun = switched("confluence.dryRun");
        this.moveExistingPages = switched("confluence.moveExistingPages");
    }

    /** What publishing would do to one page. */
    public enum Verdict {

        /** No page of this title is there: publishing would create one. */
        CREATE("would create   "),

        /** The page is there and says something else: publishing would write a new version. */
        UPDATE("would update   "),

        /** The page is there and already carries this text, hash for hash. */
        UNCHANGED("unchanged      "),

        /** The page is there but hangs somewhere else, and this run would re-parent it. */
        MOVE("would move     "),

        /** A page of this title exists under another parent, and a title is unique per space. */
        CONFLICT("would fail     ");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public ConfigObject getConfig() {
        return config;
    }

    public ConfluenceClient getConfluenceClient() {
        return confluenceClient;
    }

    /** Replaces the client, which is how a test puts a recorder in its place. */
    public void setConfluenceClient(ConfluenceClient confluenceClient) {
        this.confluenceClient = confluenceClient;
    }

    @Override
    public void execute() {
        if (dryRun) {
            System.out.println("dry run: reading what is there, writing nothing");
        }
        for (Map<?, ?> input : inputs()) {
            Object file = input.get("file");
            if (file == null) {
                continue;
            }
            publish(input, String.valueOf(file));
        }
        if (dryRun) {
            summarise();
        }
    }

    /**
     * @return what a dry run found, by verdict. Empty after a run that published.
     */
    public Map<Verdict, Integer> getVerdicts() {
        return verdicts;
    }

    private void summarise() {
        System.out.println();
        int pages = verdicts.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("dry run over " + pages + " page(s):");
        for (Verdict verdict : Verdict.values()) {
            int count = verdicts.getOrDefault(verdict, 0);
            if (count > 0) {
                System.out.println("  " + verdict.name().toLowerCase(Locale.ROOT) + ": " + count);
            }
        }
        int changes = verdicts.getOrDefault(Verdict.CREATE, 0)
                + verdicts.getOrDefault(Verdict.UPDATE, 0)
                + verdicts.getOrDefault(Verdict.MOVE, 0);
        System.out.println(changes == 0
                ? "nothing would change."
                : changes + " page(s) would be written. Attachments and labels are not compared "
                        + "in a dry run; they are written after the page they belong to.");
    }

    /**
     * Says what is in the way, where it is, and what can be done about it.
     *
     * <p>The old message named the title and the id and left the reader to find out the rest. What
     * decides the next step is whether this publisher wrote that page - then moving it is what the
     * author means - and where it currently hangs.</p>
     */
    private String conflict(String title, Map<?, ?> existing, String requestedParentId,
                            boolean ours) {
        String id = String.valueOf(existing.get("id"));
        return "'" + title + "' cannot be created: a page of that title already exists in space "
                + confluenceSpaceKey + " (id " + id + ", " + pageUrl(id) + "), and a Confluence "
                + "title is unique per space."
                + System.lineSeparator() + "    it hangs under " + existing.get("parentId")
                + ", this document wants it under " + requestedParentId + "."
                + System.lineSeparator() + "    " + (ours
                        ? "It carries this publisher's hash, so it is a page of an earlier run: "
                                + "publish with confluence.moveExistingPages (--move) to move it "
                                + "here instead of failing."
                        : "It carries no hash of this publisher, so it was written by somebody "
                                + "else. Moving it is refused; rename the heading, or set "
                                + "confluence.pagePrefix to keep this document's titles apart.");
    }

    /** @return where a reader can open that page, built from the API URL the run is using */
    private String pageUrl(String pageId) {
        return spaceUrl() + "/pages/" + pageId;
    }

    /**
     * @return the space as a reader opens it. Built from the API URL, with the trailing slashes
     *         taken off: "…/rest/api/" left one behind, and the link then carried "//spaces/".
     */
    private String spaceUrl() {
        return String.valueOf(configService.getConfigProperty("confluence.api"))
                .replace("rest/api/", "")
                .replaceAll("/+$", "")
                + "/spaces/" + confluenceSpaceKey;
    }

    /** @return the switch, read without Groovy truth so that an explicit false is a decision */
    private boolean switched(String path) {
        Object configured = configService.getRawConfigProperty(path);
        return configured instanceof Boolean set
                ? set : Boolean.parseBoolean(String.valueOf(configured));
    }

    /**
     * Records and prints one page's verdict.
     *
     * @return the id the children of this page hang under, or a placeholder where there is none
     *         yet - a page that does not exist has no id to give them
     */
    private String report(Verdict verdict, String title, String id, String detail) {
        verdicts.merge(verdict, 1, Integer::sum);
        System.out.println("  " + verdict + title + (detail.isEmpty() ? "" : "   (" + detail + ")"));
        return id;
    }

    private void publish(Map<?, ?> input, String file) {
        PublishSettings settings = PublishSettings.of(input, confluenceSection());
        confluenceSpaceKey = settings.spaceKey();
        confluencePagePrefix = settings.pagePrefix();
        confluencePageSuffix = settings.pageSuffix();

        String canonical = confluenceService.checkAndBuildCanonicalFileName(file);
        File htmlFile = new File(canonical);
        baseUrl = canonical;

        System.out.println("Publish " + canonical + " to " + confluenceSpaceKey + " at "
                + configService.getConfigProperty("confluence.api") + " ...");
        Document dom = confluenceService.parseFile(htmlFile);

        String parentId = parentIdFor(settings);
        List<String> keywords = confluenceService.getKeywords(dom);

        PageTree tree = new PageTreeBuilder(settings.footnoteLabel())
                .build(dom, parentId, settings.subpagesForSections());
        List<String> published =
                pushPages(tree.getPages(), tree.getAnchors(), tree.getPageAnchors(), keywords);

        // The page this document starts at, which is what a reader wants to open. It used to name
        // the ancestor it was published under, or the whole space where there was none - neither
        // of which says where the document now is.
        String start = published.stream()
                .filter(id -> id != null && !NOT_CREATED.equals(id))
                .findFirst()
                .orElse(null);
        if (start != null) {
            System.out.println((dryRun ? "would publish to " : "published to ") + pageUrl(start));
        } else if (dryRun) {
            System.out.println("would publish to " + spaceUrl()
                    + " (the pages do not exist yet, so they have no address)");
        } else {
            System.out.println("published to " + spaceUrl());
        }
        reportLeftBehind(parentId, start);
    }

    /**
     * Names the pages below this document that this run did not touch.
     *
     * <p>A page is found by its title, so renaming a heading does not rename the page: the new
     * title creates a new page and the old one stays where it was, and the space quietly grows a
     * second copy. Nothing reported that until it was noticed in Confluence.</p>
     *
     * <p>Only pages carrying this publisher's hash are named, and only below what this document
     * owns - its own start page, and, where one is configured, the children of its ancestor. A
     * page somebody else wrote is not this run's business.</p>
     */
    private void reportLeftBehind(String parentId, String start) {
        Map<?, ?> listing = retrieveAllPages(confluenceSpaceKey);
        List<String> candidates = new ArrayList<>();
        for (String id : belowThisDocument(listing, parentId, start)) {
            if (written.contains(id)) {
                continue;
            }
            Map<?, ?> page = asMap(confluenceClient.retrieveFullPageById(id));
            if (!remoteHashOf(page).isEmpty()) {
                candidates.add(id + "  " + page.get("title") + "  " + pageUrl(id));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(">>> " + candidates.size() + " page(s) below this document were written "
                + "by an earlier run and are not part of it any more.");
        System.out.println(">>> A renamed heading leaves the old page behind, because a page is "
                + "found by its title:");
        candidates.forEach(candidate -> System.out.println(">>>   " + candidate));
        System.out.println(">>> Move or delete them in Confluence; this run does not touch them.");
    }

    /**
     * @return the ids this document owns: everything below its start page, and where an ancestor
     *         is configured, that ancestor's other children - a renamed root page is left there
     */
    private Set<String> belowThisDocument(Map<?, ?> listing, String parentId, String start) {
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        for (Object entry : listing.values()) {
            if (entry instanceof Map<?, ?> page) {
                childrenByParent
                        .computeIfAbsent(String.valueOf(page.get("parentId")), id -> new ArrayList<>())
                        .add(String.valueOf(page.get("id")));
            }
        }
        Set<String> owned = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (start != null) {
            queue.addAll(childrenByParent.getOrDefault(start, List.of()));
        }
        if (parentId != null && !parentId.isEmpty()) {
            queue.addAll(childrenByParent.getOrDefault(parentId, List.of()));
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (owned.add(id)) {
                queue.addAll(childrenByParent.getOrDefault(id, List.of()));
            }
        }
        return owned;
    }

    /**
     * @return the page to publish under: the one named, else the one configured, else nothing -
     *         which creates the page at the root of the space
     */
    private String parentIdFor(PublishSettings settings) {
        if (!settings.ancestorName().isEmpty()) {
            Object found = retrievePageIdByName(settings.ancestorName());
            System.out.println("Retrieved pageId for given ancestorName '"
                    + settings.ancestorName() + "' is " + found);
            if (found != null) {
                return String.valueOf(found);
            }
        }
        return settings.ancestorId().isEmpty() ? null : settings.ancestorId();
    }

    /**
     * @return the configured inputs, with every file of {@code inputHtmlFolder} added to them
     */
    private List<Map<?, ?>> inputs() {
        List<Map<?, ?>> inputs = new ArrayList<>();
        Object configured = confluenceSection().get("input");
        if (configured instanceof Collection<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> input) {
                    inputs.add(input);
                }
            }
        }
        inputs.addAll(inputsFromFolder());
        return inputs;
    }

    /** Visible to its test: what a configured folder turns into is worth stating on its own. */
    List<Map<?, ?>> inputsFromFolder() {
        Object folder = configService.getConfigProperty("confluence.inputHtmlFolder");
        if (folder == null) {
            return List.of();
        }
        System.out.println("Starting processing files in folder: " + folder);
        List<Map<?, ?>> found = new ArrayList<>();
        Path root = Path.of(docDir, String.valueOf(folder));
        try (Stream<Path> files = Files.walk(root)) {
            // Relative to the folder that was walked, and composed as a path: a folder named
            // without a trailing slash would otherwise run into the file name, and two pages of
            // the same name in different subfolders would become one.
            files.filter(Files::isRegularFile).forEach(file -> found.add(Map.of("file",
                    Path.of(String.valueOf(folder)).resolve(root.relativize(file)).toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + folder, e);
        }
        return found;
    }

    /**
     * @return the ids of the pages pushed at this level, so the caller can name where the document
     *         now starts
     */
    private List<String> pushPages(List<Page> pages, Map<String, String> anchors,
                                   Map<String, String> pageAnchors, List<String> labels) {
        List<String> ids = new ArrayList<>();
        for (Page page : pages) {
            page.setTitle(page.getTitle().trim());
            System.out.println(page.getTitle());
            String id = pushToConfluence(page, anchors, pageAnchors, labels);
            ids.add(id);
            written.add(id);
            page.getChildren().forEach(child -> child.setParent(id));
            pushPages(page.getChildren(), anchors, pageAnchors, labels);
        }
        return ids;
    }

    /**
     * Writes one page, and returns its id so its children know where they hang.
     *
     * <p>An unchanged page is not rewritten: the body carries the hash of what produced it, and a
     * matching hash means Confluence already holds this text. Rewriting it would bump the version
     * and notify every watcher for nothing.</p>
     */
    private String pushToConfluence(Page localPage, Map<String, String> anchors,
                                    Map<String, String> pageAnchors, List<String> keywords) {
        String parentId = localPage.getParent();
        String title = realTitle(localPage.getTitle());

        PublishedBody built = new BodyBuilder(configService, baseUrl,
                confluencePagePrefix, confluencePageSuffix)
                .build(localPage.getBody(), anchors, pageAnchors);
        String localHash = ContentHash.md5(built.storageFormat());
        String body = decorate(built.storageFormat());

        Map<?, ?> existing = existingPage(title);
        boolean elsewhere = existing != null && !hasRequestedParent(existing, parentId);
        Map<?, ?> page = existing != null && !elsewhere
                ? asMap(confluenceClient.retrieveFullPageById(String.valueOf(existing.get("id"))))
                : null;
        boolean moving = false;

        if (elsewhere) {
            // A title is unique per space, so this page cannot simply be created beside the one
            // that holds the title. Either it is one of ours and can be moved here, or it belongs
            // to somebody else and the run has to say so.
            Map<?, ?> conflicting =
                    asMap(confluenceClient.retrieveFullPageById(String.valueOf(existing.get("id"))));
            boolean ours = !remoteHashOf(conflicting).isEmpty();
            if (moveExistingPages && ours) {
                page = conflicting;
                moving = true;
            } else {
                String problem = conflict(title, existing, parentId, ours);
                if (dryRun) {
                    // Reported rather than thrown: a dry run that stops at the first problem
                    // hides the others, which is the opposite of what it is for.
                    return report(Verdict.CONFLICT, title, NOT_CREATED, problem);
                }
                throw new IllegalArgumentException(problem);
            }
        }

        if (page == null) {
            if (dryRun) {
                return report(Verdict.CREATE, title, NOT_CREATED,
                        built.uploads().isEmpty()
                                ? "" : built.uploads().size() + " attachment(s) with it");
            }
            Map<?, ?> created = asMap(confluenceClient.createPage(
                    title, confluenceSpaceKey, body, versionComment(), parentId));
            String id = created == null ? null : String.valueOf(created.get("id"));
            System.out.println("> created page " + id);
            finish(id, built.uploads(), keywords);
            return id;
        }

        String id = String.valueOf(page.get("id"));
        Map<?, ?> version = asMap(page.get("version"));
        System.out.println("found existing page: " + id + " version " + version.get("number"));

        // A page that is being moved is written even where its text is unchanged: the ancestor
        // travels with the update, so skipping the write would leave the page where it was.
        if (!moving && localHash.equals(remoteHashOf(page))) {
            if (dryRun) {
                return report(Verdict.UNCHANGED, title, id, "id " + id);
            }
            System.out.println("page hasn't changed!");
            finish(id, built.uploads(), keywords);
            return id;
        }

        int nextVersion = Integer.parseInt(String.valueOf(version.get("number"))) + 1;
        if (dryRun) {
            return report(moving ? Verdict.MOVE : Verdict.UPDATE, title, id,
                    moving
                            ? "id " + id + ", from parent " + existing.get("parentId")
                                    + " to " + parentId
                            : "id " + id + ", version " + version.get("number")
                                    + " to " + nextVersion);
        }
        confluenceClient.updatePage(id, title, confluenceSpaceKey, body, nextVersion,
                versionComment(), parentId);
        System.out.println(moving
                ? "> moved page " + id + " to parent " + parentId
                : "> updated page " + id);
        finish(id, built.uploads(), keywords);
        return id;
    }

    /**
     * Attaches the files the body refers to and adds the document's keywords as labels. Both need
     * the page to exist, which is why neither can travel with it.
     */
    private void finish(String pageId, List<Upload> uploads, List<String> keywords) {
        uploads.forEach(upload ->
                uploadAttachment(pageId, upload.url(), upload.fileName(), upload.comment()));
        if (keywords != null && !keywords.isEmpty()) {
            addLabels(pageId, keywords);
        }
    }

    private String decorate(String body) {
        // Read through configService: an unset ConfigObject entry coerced with "as String" becomes
        // the literal "[:]", which would be published as page content.
        return new PageDecorator(
                configService.getConfigProperty("confluence.disableToC") != null,
                text(configService.getConfigProperty("confluence.extraPageContent")),
                text(configService.getConfigProperty("confluence.tableOfContents")),
                text(configService.getConfigProperty("confluence.tableOfChildren"))).decorate(body);
    }

    private Map<?, ?> existingPage(String title) {
        Object found = retrieveAllPages(confluenceSpaceKey).get(title.toLowerCase(java.util.Locale.ROOT));
        return found instanceof Map<?, ?> page ? page : null;
    }

    /**
     * @return whether this page already hangs where the document wants it
     */
    private static boolean hasRequestedParent(Map<?, ?> existingPage, String requestedParentId) {
        if (requestedParentId == null || requestedParentId.isEmpty()) {
            return true;
        }
        return requestedParentId.equals(String.valueOf(existingPage.get("parentId")));
    }

    private static String remoteHashOf(Map<?, ?> page) {
        Map<?, ?> storage = asMap(asMap(page.get("body")).get("storage"));
        Matcher matcher = REMOTE_HASH.matcher(String.valueOf(storage.get("value")).trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * Lists the pages the document may already have, keyed by lower-cased title.
     *
     * <p>Kept between the pages of one run, because a document of forty sections would otherwise
     * ask for the same listing forty times. Kept per space: an input that names its own space is
     * asking about somewhere else, and must neither be answered from another space's listing nor
     * throw that listing away.</p>
     */
    private Map<?, ?> retrieveAllPages(String spaceKey) {
        Map<?, ?> listed = allPagesBySpace.get(spaceKey);
        if (listed != null) {
            System.out.println("allPages already retrieved");
            return listed;
        }
        List<String> ancestorIds = new ArrayList<>();
        boolean wholeSpace = false;
        for (Map<?, ?> input : inputs()) {
            // Only the inputs that publish into this space. An ancestor of another space would
            // otherwise be listed as if it were here, and its titles would look like pages of
            // this space that this document is about to collide with.
            if (!spaceKey.equals(effectiveSpaceKey(input))) {
                continue;
            }
            Object ancestorId = input.get("ancestorId");
            if (ancestorId == null) {
                // One input without an ancestor means the document may go anywhere in the space.
                wholeSpace = true;
                break;
            }
            ancestorIds.add(String.valueOf(ancestorId));
        }
        System.out.println(".");

        int pageLimit = pageLimit();
        Map<?, ?> allPages;
        if (wholeSpace) {
            allPages = confluenceClient.fetchPagesBySpaceKey(spaceKey, pageLimit);
        } else if (ancestorIds.isEmpty()) {
            // Nothing publishes into this space under an ancestor, so there is nothing to ask
            // about - and asking for no ancestors at all would answer with the whole space.
            allPages = Map.of();
        } else {
            allPages = confluenceClient.fetchPagesByAncestorId(ancestorIds, pageLimit);
        }
        System.out.println(allPages.size() + " pages retrieved");
        allPagesBySpace.put(spaceKey, allPages);
        return allPages;
    }

    /** @return the space this input publishes into, its own where it names one */
    private String effectiveSpaceKey(Map<?, ?> input) {
        Object named = input.get("spaceKey");
        return named == null ? String.valueOf(spaceKeyOfSection()) : String.valueOf(named);
    }

    private Object spaceKeyOfSection() {
        return confluenceSection().get("spaceKey");
    }

    private int pageLimit() {
        Object configured = configService.getConfigProperty("confluence.pageLimit");
        if (configured instanceof Number number) {
            return number.intValue();
        }
        return configured == null ? DEFAULT_PAGE_LIMIT : Integer.parseInt(String.valueOf(configured));
    }

    private Object retrievePageIdByName(String name) {
        Map<?, ?> data = asMap(confluenceClient.retrievePageIdByName(name, confluenceSpaceKey));
        Object results = data.get("results");
        if (results instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return first.get("id");
        }
        return null;
    }

    /**
     * Adds the document's keywords as labels, one call each - Confluence takes labels only after a
     * page exists, and only one at a time. Existing labels of the same name are replaced; labels
     * for keywords that were removed from the document are not deleted.
     */
    private void addLabels(String pageId, List<String> labels) {
        for (String label : labels) {
            // 'global' is the only prefix an ordinary Confluence accepts.
            confluenceClient.addLabel(pageId, Map.of("prefix", "global", "name", label));
            System.out.println("added label " + label + " to page ID " + pageId);
        }
    }

    private void uploadAttachment(String pageId, String url, String fileName, String note) {
        byte[] content = read(url);
        // Hashed as text with the platform's charset, as it has always been. The charset is a
        // recorded defect; changing it would make every attachment of every page look changed
        // once, so it waits for a migration rather than riding along with a port.
        String localHash = ContentHash.md5(new String(content, Charset.defaultCharset()));

        Map<?, ?> attachment = asMap(confluenceClient.getAttachment(pageId, fileName));
        Object results = attachment.get("results");
        boolean exists = results instanceof List<?> list && !list.isEmpty();
        if (!exists) {
            confluenceClient.createAttachment(pageId, stream(content), fileName, note, localHash);
            return;
        }
        if (Boolean.TRUE.equals(confluenceClient.attachmentHasChanged(attachment, localHash))) {
            String attachmentId = String.valueOf(((Map<?, ?>) ((List<?>) results).get(0)).get("id"));
            confluenceClient.updateAttachment(pageId, attachmentId, stream(content),
                    fileName, note, localHash);
            System.out.println("    updated attachment");
        }
    }

    private static byte[] read(String url) {
        try {
            if (url.startsWith("http")) {
                try (InputStream stream = URI.create(url).toURL().openStream()) {
                    return stream.readAllBytes();
                }
            }
            return Files.readAllBytes(Path.of(url));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the attachment " + url, e);
        }
    }

    private static InputStream stream(byte[] content) {
        return new java.io.ByteArrayInputStream(content);
    }

    /**
     * @return the title as it appears in Confluence, where a prefix or suffix keeps one document
     *         apart from another published into the same space
     */
    private String realTitle(String pageTitle) {
        return confluencePagePrefix + pageTitle + confluencePageSuffix;
    }

    private String versionComment() {
        return text(configService.getConfigProperty("confluence.pageVersionComment"));
    }

    private Map<?, ?> confluenceSection() {
        Object section = config.get("confluence");
        return section instanceof Map<?, ?> found ? found : new LinkedHashMap<>();
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
