package org.docToolchain.atlassian.transformer;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * Where a link out of one document lands.
     *
     * @param from the file being published, which the link is relative to
     * @param href the link as the document wrote it, fragment and all
     * @return the page and anchor to point at, or empty where this run publishes no such document
     */
    public Optional<Target> targetOf(String from, String href) {
        int hash = href.indexOf('#');
        String file = hash < 0 ? href : href.substring(0, hash);
        String fragment = hash < 0 ? "" : href.substring(hash + 1);
        Document document = documents.get(key(from, file));
        if (document == null) {
            return Optional.empty();
        }
        if (fragment.isEmpty()) {
            return Optional.of(new Target(document.rootTitle, ""));
        }
        // An anchor of its own, or a heading that became a page of its own: the first keeps the
        // anchor, the second is the page and has none to keep.
        String onPage = document.anchors.get(fragment);
        if (onPage != null) {
            return Optional.of(new Target(onPage, fragment));
        }
        String ownPage = document.pageAnchors.get(fragment);
        return ownPage == null
                // The document is published but has no such anchor. Pointing at its first page
                // would send the reader somewhere the link did not name, so this is no target.
                ? Optional.empty()
                : Optional.of(new Target(ownPage, ""));
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
