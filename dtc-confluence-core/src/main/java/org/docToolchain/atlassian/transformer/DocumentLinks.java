package org.docToolchain.atlassian.transformer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which Confluence page each of this run's documents became, so that a link from one to another
 * can find it.
 *
 * <p>An {@code xref:other.adoc[]} is an {@code <a href="other.html">} by the time this sees it, and
 * on a Confluence page that is a link to a file nobody ever put there. The page it should point at
 * does exist - this very run publishes it - but only the run as a whole knows that: each document
 * is converted before the next one has been looked at. So the titles are collected first, for
 * every input, and handed down here.</p>
 *
 * <p>A title carries the prefix and suffix of <em>its own</em> input, not of the document doing the
 * linking. Two documents may be configured differently, and the page is found by the title it was
 * actually published under.</p>
 */
public final class DocumentLinks {

    /** For a run with nothing to resolve against, which is the single-document case. */
    public static final DocumentLinks NONE = new DocumentLinks(Map.of());

    /**
     * Endings an anchor picks up when a cross-reference is mis-spelled, as in
     * {@code xref:other#the-part.adoc[]} - the extension belongs to the file, in front of the
     * {@code #}, and AsciiDoctor leaves whatever follows it alone. Only these three, because an
     * anchor may legitimately carry a dot and guessing past the last one would invent anchors.
     */
    private static final List<String> MISPLACED_EXTENSIONS = List.of(".adoc", ".html", ".htm");

    private final Map<String, Document> documents;

    private DocumentLinks(Map<String, Document> documents) {
        this.documents = documents;
    }

    public static Builder of() {
        return new Builder();
    }

    /**
     * @return whether anything was collected at all, so that a caller can skip the lookups and
     *         the warnings a single-document run has no use for
     */
    public boolean isEmpty() {
        return documents.isEmpty();
    }

    /**
     * Where a link out of one document lands, and what was wrong with it on the way.
     *
     * <p>Two different things go wrong here and they call for different words: the document a link
     * names is not part of this run, or it is published but carries no such anchor. The first
     * leaves nowhere to point; the second leaves the page, which is not what the link asked for
     * but is where the reader was headed.</p>
     *
     * @param from the file being published, which the link is relative to
     * @param href the link as the document wrote it, fragment and all
     */
    public Resolution resolve(String from, String href) {
        int hash = href.indexOf('#');
        String file = hash < 0 ? href : href.substring(0, hash);
        String fragment = hash < 0 ? "" : href.substring(hash + 1);
        Document document = documents.get(key(from, file));
        if (document == null) {
            return Resolution.nowhere();
        }
        Target wholeDocument = new Target(document.rootTitle, "");
        if (fragment.isEmpty()) {
            return Resolution.at(wholeDocument);
        }
        // An anchor of its own, or a heading that became a page of its own: the first keeps the
        // anchor, the second is the page and has none to keep.
        String onPage = document.anchors.get(fragment);
        if (onPage != null) {
            return Resolution.at(new Target(onPage, fragment));
        }
        String ownPage = document.pageAnchors.get(fragment);
        if (ownPage != null) {
            return Resolution.at(new Target(ownPage, ""));
        }
        return Resolution.missing(wholeDocument, fragment, anchorAlmostNamed(fragment, document));
    }

    /**
     * @return the anchor the link was reaching for, where it named one that exists but for a file
     *         extension left on it, or empty where nothing so simple is wrong
     */
    private static String anchorAlmostNamed(String fragment, Document document) {
        String lowercase = fragment.toLowerCase(Locale.ROOT);
        for (String extension : MISPLACED_EXTENSIONS) {
            if (!lowercase.endsWith(extension)) {
                continue;
            }
            String withoutIt = fragment.substring(0, fragment.length() - extension.length());
            if (document.anchors.containsKey(withoutIt)
                    || document.pageAnchors.containsKey(withoutIt)) {
                return withoutIt;
            }
        }
        return "";
    }

    /**
     * @return the file a link names, as an absolute path where that can be worked out. Relative to
     *         the linking document, because that is what the href is relative to - a document and
     *         the ones it refers to need not sit in one directory.
     */
    private static String key(String from, String file) {
        try {
            Path base = Path.of(from).toAbsolutePath().normalize().getParent();
            return base == null ? absolute(file) : base.resolve(file).normalize().toString();
        } catch (InvalidPathException notAPath) {
            return file;
        }
    }

    /** @return the same spelling for the same file, whichever way either side named it */
    private static String absolute(String file) {
        try {
            return Path.of(file).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException notAPath) {
            return file;
        }
    }

    /**
     * @param pageTitle the title to point at, prefix and suffix included
     * @param anchor    the anchor within it, empty where the whole page is the target
     */
    public record Target(String pageTitle, String anchor) {
    }

    /**
     * What came of a link, told apart finely enough for the run to say something true about it.
     *
     * @param target        where the link should point, empty only where this run publishes no
     *                      such document and there is therefore nowhere to point
     * @param missingAnchor the anchor the link asked for and the document does not carry, empty
     *                      unless that is what went wrong
     * @param nearestAnchor an anchor the document does carry and the link all but named, empty
     *                      where there is no such near miss
     */
    public record Resolution(Optional<Target> target, String missingAnchor, String nearestAnchor) {

        private static Resolution nowhere() {
            return new Resolution(Optional.empty(), "", "");
        }

        private static Resolution at(Target target) {
            return new Resolution(Optional.of(target), "", "");
        }

        private static Resolution missing(Target page, String anchor, String nearest) {
            return new Resolution(Optional.of(page), anchor, nearest);
        }

        /** @return whether the document the link names is one this run publishes */
        public boolean namesAPublishedDocument() {
            return target.isPresent();
        }

        /** @return whether the document was found but the anchor within it was not */
        public boolean anchorIsMissing() {
            return !missingAnchor.isEmpty();
        }
    }

    private record Document(String rootTitle, Map<String, String> anchors,
                            Map<String, String> pageAnchors) {
    }

    /** Collects one document at a time, as the run walks its inputs. */
    public static final class Builder {

        private final Map<String, Document> documents = new LinkedHashMap<>();

        /**
         * @param file       the HTML file of this input, as the run resolved it
         * @param rootTitle  the page a link with no anchor should land on, decorated
         * @param anchors    anchor to the decorated title of the page carrying it
         * @param pageAnchors heading that became a page of its own, to that page's decorated title
         */
        public Builder add(String file, String rootTitle, Map<String, String> anchors,
                           Map<String, String> pageAnchors) {
            documents.put(absolute(file), new Document(rootTitle,
                    new HashMap<>(anchors), new HashMap<>(pageAnchors)));
            return this;
        }

        public DocumentLinks build() {
            return new DocumentLinks(Map.copyOf(documents));
        }
    }
}
