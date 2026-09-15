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
     * @return the folders this page sits in, outermost first, named after the original file names
     */
    public static List<String> folderStructure(Map<?, ?> pages, String pageId) {
        return ancestorNames(pages, pageId, "filename");
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
