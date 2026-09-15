package org.docToolchain.atlassian.confluence.export;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.atlassian.confluence.clients.ConfluenceClient;
import org.docToolchain.atlassian.confluence.clients.RestClient;
import org.docToolchain.configuration.ConfigService;

/**
 * Reads pages and attachments out of Confluence for the export.
 *
 * <p>The publishing client speaks the same API but asks different questions: it wants a page by
 * title to decide whether to create or update one. An export walks a tree downwards and needs the
 * history and the space along with each body. Rather than widen the publishing client with expands
 * nothing else uses, this asks its own questions through the same {@link RestClient} - so
 * authentication, the rate limit, the proxy and the error messages are the ones already in
 * place.</p>
 *
 * <p>docToolchain's export driver opened {@code HttpURLConnection} by hand and re-implemented all
 * of that; see {@code docs/decisions.adoc}.</p>
 */
public class ConfluenceReader {

    /** Everything the converter needs about a page, in one request. */
    private static final String PAGE_EXPANDS = String.join(",",
            "body.storage", "version", "space", "ancestors",
            "history.createdBy", "history.contributors.publishers.users");

    private final RestClient restClient;
    private final int pageLimit;

    /**
     * REST v1 with the context path the configured URL carries - Data Center usually serves
     * Confluence under /confluence, and a request without it reaches nothing. Derived by
     * {@link ConfluenceClient}, so both sides read the configuration the same way.
     */
    private final String apiPath;

    /**
     * The path Confluence is served under. A download link comes back as
     * {@code /download/attachments/...}, which is relative to this and reaches nothing without it.
     */
    private final String contextPath;

    public ConfluenceReader(ConfigService configService, RestClient restClient, int pageLimit) {
        this.restClient = restClient;
        this.pageLimit = pageLimit;
        this.apiPath = ConfluenceClient.apiV1PathFor(configService);
        this.contextPath = ConfluenceClient.contextPathFor(configService);
    }

    /**
     * @return the page with its body, history and space, or {@code null} if there is no such page
     */
    public Map<?, ?> fetchPage(String pageId) {
        URI uri = uri("/content/" + pageId, "expand", PAGE_EXPANDS);
        Object response = restClient.doRequestAndReturnOrNull(new HttpGet(uri));
        return response instanceof Map<?, ?> page ? page : null;
    }

    /**
     * @return the direct children of this page, following the paging to the end
     */
    public List<Map<?, ?>> fetchChildPages(String parentId) {
        return fetchAllPages("/content/" + parentId + "/child/page", null);
    }

    /**
     * @return every attachment of this page, with its version
     */
    public List<Map<?, ?>> fetchAttachments(String pageId) {
        return fetchAllPages("/content/" + pageId + "/child/attachment", "version");
    }

    /**
     * Fetches an attachment as the bytes it is.
     *
     * @param downloadPath what Confluence gave as the attachment's download link, which is a path
     *                     below the same host rather than a full URL
     * @return the bytes, or {@code null} where there is nothing at that path any more
     */
    public byte[] download(String downloadPath) {
        // Already absolute where a caller passed a full link; otherwise it is relative to the
        // context path, which Confluence reports beside the link as "_links.context".
        // On a segment boundary: with a context path of /confluence, a link below
        // /confluence-other carries no context of ours and needs one.
        boolean carriesContext = downloadPath.equals(contextPath)
                || downloadPath.startsWith(contextPath + "/");
        String path = carriesContext || downloadPath.startsWith("http")
                ? downloadPath
                : contextPath + downloadPath;
        return restClient.doRequestAndReturnBytes(new HttpGet(URI.create(path)));
    }

    /**
     * Walks a paged collection to its end.
     *
     * <p>Confluence answers {@code start} and {@code limit} with a page of results and says nothing
     * about how many there are in total, so the end is a short answer - fewer results than asked
     * for, or none.</p>
     */
    private List<Map<?, ?>> fetchAllPages(String path, String expand) {
        List<Map<?, ?>> found = new ArrayList<>();
        for (int start = 0; ; start += pageLimit) {
            URIBuilder builder = builderFor(path)
                    .addParameter("limit", String.valueOf(pageLimit))
                    .addParameter("start", String.valueOf(start));
            if (expand != null) {
                builder.addParameter("expand", expand);
            }
            Object response = restClient.doRequestAndFailIfNot20x(new HttpGet(build(builder)));
            if (!(response instanceof Map<?, ?> body)) {
                return found;
            }
            Object results = body.get("results");
            if (!(results instanceof List<?> list) || list.isEmpty()) {
                return found;
            }
            for (Object result : list) {
                if (result instanceof Map<?, ?> entry) {
                    found.add(entry);
                }
            }
            if (list.size() < pageLimit) {
                return found;
            }
        }
    }

    private URI uri(String path, String name, String value) {
        return build(builderFor(path).addParameter(name, value));
    }

    private URIBuilder builderFor(String path) {
        return new URIBuilder().setPath(apiPath + path);
    }

    private static URI build(URIBuilder builder) {
        try {
            return builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot build the request URI", e);
        }
    }
}
