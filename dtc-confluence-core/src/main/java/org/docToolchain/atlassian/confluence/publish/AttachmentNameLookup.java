package org.docToolchain.atlassian.confluence.publish;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.docToolchain.atlassian.confluence.export.PageNaming;

/**
 * Finds the attachment a reference of the document came from, by asking the page what it holds.
 *
 * <p>Confluence accepts a space, a colon and a path separator in an attachment name; the first
 * two are awkward in a path and in an AsciiDoc image macro and the third would reach into another
 * directory, so an export writes every attachment through
 * {@link PageNaming#sanitizeAttachmentName}. The document then refers to {@code my_image.png}
 * where Confluence holds {@code my image.png}, and publishing that reference literally attaches a
 * second file beside the original: the page's pictures and macros split across the two, and a
 * diagram an app binds by name can never be rebound.</p>
 *
 * <p>The sanitisation cannot be undone. {@code my_image.png} is a legal attachment name of its
 * own, so no rule over the reference alone can say which {@code _} was once a space - the mapping
 * is not injective and guessing it would write over a file nobody named. The question is
 * therefore put to the page: of the attachments it holds, which one does the export write under
 * this name? That is the sanitisation read forwards, over names the server gave.</p>
 */
public class AttachmentNameLookup {

    /** For a page that holds nothing, which is every page about to be created. */
    public static final AttachmentNameLookup NONE = new AttachmentNameLookup(List::of);

    private final Supplier<List<String>> remoteNames;

    /** The names of the page, by the name an export of them would write. Null until asked for. */
    private Map<String, Set<String>> heldByExportedName;

    /**
     * @param remoteNames what the page holds, asked for at most once and only where a document
     *                    has something to match - a page is one request, and most documents have
     *                    no attachments at all
     */
    public AttachmentNameLookup(Supplier<List<String>> remoteNames) {
        this.remoteNames = remoteNames;
    }

    /**
     * @param reference the name the document refers to the file by, which is the name an export
     *                  wrote it to disk under
     * @return the name Confluence holds that file under, or {@code reference} where the page holds
     *         nothing that could be it and where it holds more than one thing that could
     */
    public String resolve(String reference) {
        Set<String> candidates = held().getOrDefault(reference, Set.of());
        if (candidates.isEmpty()) {
            return reference;
        }
        if (candidates.size() > 1) {
            report(reference, candidates);
            return reference;
        }
        String name = candidates.iterator().next();
        if (!name.equals(reference)) {
            System.out.println("    attachment: '" + reference + "' is '" + name
                    + "' on the page, which is the name it goes back under");
        }
        return name;
    }

    /**
     * Says which attachments a reference could be and what happens instead, because the
     * alternative - taking one of them - writes a new revision of a file the document may never
     * have been exported from.
     */
    private static void report(String reference, Set<String> candidates) {
        System.out.println(">>> WARN: more than one attachment of this page is exported as '"
                + reference + "': " + String.join(", ", quoted(candidates))
                + ". The export replaces whitespace, a colon and a path separator with '_', so "
                + "which of them this file came from cannot be read off its name.");
        System.out.println("    It is published as '" + reference + "', the name the document "
                + "refers to. Renaming the attachment in Confluence, or the file here, is what "
                + "ends the ambiguity.");
    }

    private static Set<String> quoted(Collection<String> names) {
        Set<String> quoted = new LinkedHashSet<>();
        names.forEach(name -> quoted.add("'" + name + "'"));
        return quoted;
    }

    /**
     * The page's attachments keyed by the name an export writes them under, which is the name the
     * document refers to them by.
     */
    private Map<String, Set<String>> held() {
        if (heldByExportedName != null) {
            return heldByExportedName;
        }
        heldByExportedName = new LinkedHashMap<>();
        List<String> names = remoteNames.get();
        // A page that holds nothing and an answer that reported nothing match nothing alike.
        if (names != null) {
            names.forEach(name -> heldByExportedName
                    .computeIfAbsent(PageNaming.sanitizeAttachmentName(name),
                            exported -> new LinkedHashSet<>())
                    .add(name));
        }
        return heldByExportedName;
    }
}
