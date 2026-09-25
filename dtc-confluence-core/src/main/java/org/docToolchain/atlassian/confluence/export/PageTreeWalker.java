package org.docToolchain.atlassian.confluence.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a Confluence page tree downwards from one root and collects what the converter needs.
 *
 * <p>Breadth first, so that a page's position among its siblings is the order Confluence returned
 * them in - which is the order the exported document is written in.</p>
 *
 * <p>A page that cannot be read is recorded and skipped rather than ending the export: a tree of a
 * few hundred pages usually holds one whose permissions differ, and losing the other pages over it
 * helps nobody.</p>
 */
public class PageTreeWalker {

    private final ConfluenceReader reader;
    private final String stripPagePrefixRegex;

    public PageTreeWalker(ConfluenceReader reader) {
        this(reader, "");
    }

    /**
     * @param stripPagePrefixRegex applied to the sanitised file name to shorten paths, or empty to
     *                             leave the names as they are
     */
    public PageTreeWalker(ConfluenceReader reader, String stripPagePrefixRegex) {
        this.reader = reader;
        this.stripPagePrefixRegex = stripPagePrefixRegex == null ? "" : stripPagePrefixRegex;
    }

    /**
     * @param rootPageId the page to start from; it and everything below it is collected
     * @return what was found
     * @throws IllegalStateException if the root itself cannot be read - with no root there is
     *                               nothing to export
     */
    public ExportedTree walk(String rootPageId) {
        Map<?, ?> rootPage = reader.fetchPage(rootPageId);
        if (rootPage == null) {
            throw new IllegalStateException("Root page " + rootPageId
                    + " is not there, or not readable with these credentials");
        }

        ExportedTree tree = new ExportedTree();
        tree.setSpace(spaceOf(rootPage, rootPageId));

        Deque<Queued> queue = new ArrayDeque<>();
        queue.add(new Queued(rootPageId, "0", 0));
        Set<String> visited = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            Queued entry = queue.poll();
            // A page can be reached twice only through a malformed tree, but reading it twice
            // would also queue its children twice, and that does not end.
            if (!visited.add(entry.id())) {
                continue;
            }

            Map<?, ?> page = entry.id().equals(rootPageId) ? rootPage : reader.fetchPage(entry.id());
            if (page == null) {
                System.out.println(">>> WARN: page " + entry.id()
                        + " could not be read, skipping it and its children");
                tree.getUnreadable().add(entry.id());
                continue;
            }
            tree.getPages().put(entry.id(), describe(page, entry));
            // The body came with the page that was just read. Keeping it here is what spares the
            // export a second request per page.
            tree.getBodies().put(entry.id(), storageOf(page));

            List<Map<?, ?>> children = reader.fetchChildPages(entry.id());
            List<String> childIds = new ArrayList<>();
            for (int position = 0; position < children.size(); position++) {
                String childId = String.valueOf(children.get(position).get("id"));
                childIds.add(childId);
                queue.add(new Queued(childId, entry.id(), position));
            }
            tree.getChildrenByParent().put(entry.id(), childIds);

            for (Map<?, ?> attachment : reader.fetchAttachments(entry.id())) {
                tree.getAttachments().put(String.valueOf(attachment.get("id")),
                        describe(attachment, entry.id()));
            }
        }
        return tree;
    }

    /**
     * @return the page's storage format, empty where the page has none - a page can be empty, and
     *         an empty page is not a failure
     */
    private static String storageOf(Map<?, ?> page) {
        if (!(page.get("body") instanceof Map<?, ?> body)) {
            return "";
        }
        if (!(body.get("storage") instanceof Map<?, ?> storage)) {
            return "";
        }
        return text(storage.get("value"));
    }

    private static Map<String, Object> spaceOf(Map<?, ?> rootPage, String rootPageId) {
        Object space = rootPage.get("space");
        Map<?, ?> fields = space instanceof Map<?, ?> found ? found : Map.of();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("name", text(fields.get("name")));
        described.put("key", text(fields.get("key")));
        described.put("homePage", rootPageId);
        return described;
    }

    private Map<String, Object> describe(Map<?, ?> page, Queued entry) {
        String filename = PageNaming.sanitizeFilename(text(page.get("title")));
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("title", page.get("title"));
        described.put("parentId", entry.parentId());
        described.put("filename", filename);
        // The regex is applied to the sanitised name rather than to the raw title, so an author
        // can write it against the underscore form they see in the output.
        described.put("adocFilename", stripPagePrefixRegex.isEmpty()
                ? filename : filename.replaceFirst(stripPagePrefixRegex, ""));
        described.put("position", String.valueOf(entry.position()));
        described.put("status", "current");
        described.put("contributors", contributorsOf(page));
        return described;
    }

    private static Map<String, Object> describe(Map<?, ?> attachment, String pageId) {
        String title = text(attachment.get("title"));
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("filename", title);
        // What Confluence stored, before anything renames it - the storage format refers to the
        // attachment by this name, so a later rename has to keep both.
        described.put("originalFilename", title);
        described.put("id", text(attachment.get("id")));
        described.put("version", versionOf(attachment));
        described.put("pageId", pageId);
        described.put("originalId", "");
        described.put("downloadUrl", downloadUrlOf(attachment));
        return described;
    }

    private static String versionOf(Map<?, ?> attachment) {
        if (attachment.get("version") instanceof Map<?, ?> version
                && version.get("number") != null) {
            return String.valueOf(version.get("number"));
        }
        return "1";
    }

    private static String downloadUrlOf(Map<?, ?> attachment) {
        if (attachment.get("_links") instanceof Map<?, ?> links) {
            return text(links.get("download"));
        }
        return "";
    }

    /**
     * @return who wrote the page, creator first, without repeating anyone
     */
    private static List<String> contributorsOf(Map<?, ?> page) {
        List<String> names = new ArrayList<>();
        if (!(page.get("history") instanceof Map<?, ?> history)) {
            return names;
        }
        addName(names, history.get("createdBy"));
        if (history.get("contributors") instanceof Map<?, ?> contributors
                && contributors.get("publishers") instanceof Map<?, ?> publishers
                && publishers.get("users") instanceof List<?> users) {
            users.forEach(user -> addName(names, user));
        }
        return names;
    }

    private static void addName(List<String> names, Object user) {
        if (user instanceof Map<?, ?> fields) {
            String name = text(fields.get("displayName"));
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** A page waiting to be read, with where it hangs and in which position. */
    private record Queued(String id, String parentId, int position) {
    }
}
