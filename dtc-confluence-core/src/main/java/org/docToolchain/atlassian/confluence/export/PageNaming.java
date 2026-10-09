package org.docToolchain.atlassian.confluence.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns page titles into names a file system accepts, and pages into the folders they sit in.
 *
 * <p>Pure functions over the page map an export builds, so that both the converter and the walk
 * that feeds it can use them without one depending on the other.</p>
 */
public final class PageNaming {

    /** What a root page carries instead of a parent, in either spelling it arrives in. */
    private static final Set<String> NO_PARENT = Set.of("", "0", "null");

    private PageNaming() {
    }

    /**
     * @return the title with everything a path or an AsciiDoc include would stumble over replaced
     */
    public static String sanitizeFilename(String title) {
        return title
                .replaceAll("[Ää]", "ae")
                .replaceAll("[Üü]", "ue")
                .replaceAll("[Öö]", "oe")
                .replaceAll("[^a-zA-Z0-9]", "_")
                .replaceAll("_+", "_");
    }

    /**
     * Confluence accepts a colon, a space, and a path separator in an attachment name. The first
     * two are awkward in a path and in an AsciiDoc image macro; the third would reach into another
     * directory, and the name comes from the server rather than from here.
     *
     * @return the name as a single path segment
     */
    public static String sanitizeAttachmentName(String filename) {
        return filename.replaceAll("[\\\\/:\\s]", "_");
    }

    /**
     * @return the folders this page sits in, outermost first, named after the original file names
     */
    public static List<String> folderStructure(Map<?, ?> pages, String pageId) {
        return ancestorNames(pages, pageId, "filename");
    }

    /**
     * Where the attachments of a page are written: the folders of the page, and then one named
     * after the page itself.
     *
     * <p>The page itself has to be in the path. Named by its ancestors alone, two children of one
     * parent shared a directory, and two attachments called {@code diagram.png} at version 1
     * became one file - whichever page was walked last decided what both documents showed.</p>
     *
     * @return the folders below the image directory, outermost first
     */
    public static List<String> attachmentFolders(Map<?, ?> pages, String pageId) {
        List<String> folders = new ArrayList<>(folderStructure(pages, pageId));
        String own = ownFolder(pages, pageId);
        if (!own.isEmpty()) {
            folders.add(own);
        }
        return folders;
    }

    /**
     * The last folder of {@link #attachmentFolders}: the page itself.
     *
     * <p>The sanitised title is not unique - "A B" and "A-B" both become "A_B" - and the export
     * disambiguates only the name the AsciiDoc file is written under, not this one. So where
     * another page would name the same folder, every page in that group carries its id: a folder
     * is named after one page or it is not a folder per page, and the attachments of two
     * siblings overwrite each other again.</p>
     *
     * <p>Decided from the page map alone, so that the downloader and the document agree without
     * either telling the other.</p>
     */
    public static String ownFolder(Map<?, ?> pages, String pageId) {
        String name = nameOf(pages.get(pageId), "filename");
        return name.isEmpty() || !isSharedWithAnotherPage(pages, pageId, name)
                ? name
                : name + "_" + pageId;
    }

    /** Whether another page sits in the same folders under the same name. */
    private static boolean isSharedWithAnotherPage(Map<?, ?> pages, String pageId, String name) {
        List<String> folders = folderStructure(pages, pageId);
        for (Map.Entry<?, ?> entry : pages.entrySet()) {
            String otherId = String.valueOf(entry.getKey());
            if (otherId.equals(pageId)) {
                continue;
            }
            if (name.equals(nameOf(entry.getValue(), "filename"))
                    && folders.equals(folderStructure(pages, otherId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same, named after {@code adocFilename} - the prefix-stripped name the AsciiDoc files are
     * written under. Where no prefix regex is configured the two are identical.
     */
    public static List<String> adocFolderStructure(Map<?, ?> pages, String pageId) {
        return ancestorNames(pages, pageId, "adocFilename");
    }

    private static List<String> ancestorNames(Map<?, ?> pages, String pageId, String nameKey) {
        List<String> folders = new ArrayList<>();
        // By identity of the ids already walked: a tree that names itself as its own ancestor
        // would otherwise recurse until the stack runs out.
        Set<String> seen = new LinkedHashSet<>();
        String current = pageId;
        seen.add(current);
        while (true) {
            String parentId = parentOf(pages, current);
            // Stop before naming a page already walked. A page that claims itself as its parent
            // is not its own folder, and a cycle must not name any page in it twice.
            if (parentId == null || !seen.add(parentId)) {
                break;
            }
            Object parent = pages.get(parentId);
            if (!(parent instanceof Map<?, ?> parentPage)) {
                System.out.println("parent page not found: " + parentId + " for "
                        + nameOf(pages.get(current), "filename"));
                return List.of();
            }
            folders.add(nameOf(parentPage, nameKey));
            current = parentId;
        }
        Collections.reverse(folders);
        return folders;
    }

    /**
     * @return the id of this page's parent, or {@code null} where it has none
     */
    private static String parentOf(Map<?, ?> pages, String pageId) {
        if (!(pages.get(pageId) instanceof Map<?, ?> page)) {
            return null;
        }
        Object parentId = page.get("parentId");
        if (parentId == null) {
            return null;
        }
        String asText = String.valueOf(parentId);
        return NO_PARENT.contains(asText) ? null : asText;
    }

    /**
     * @return the page's name under {@code key}, falling back to its filename where the
     *         prefix-stripped one was never set
     */
    private static String nameOf(Object page, String key) {
        if (!(page instanceof Map<?, ?> fields)) {
            return "";
        }
        Object name = fields.get(key);
        if (name == null || String.valueOf(name).isEmpty()) {
            name = fields.get("filename");
        }
        return name == null ? "" : String.valueOf(name);
    }
}
