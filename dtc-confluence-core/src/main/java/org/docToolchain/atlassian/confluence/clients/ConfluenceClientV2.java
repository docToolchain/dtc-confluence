package org.docToolchain.atlassian.confluence.clients;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import groovy.json.JsonBuilder;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.configuration.ConfigService;

/**
 * Speaks the Confluence REST API v2, which exists in Cloud only.
 *
 * <p>v2 addresses a space by id rather than by key, so the key is resolved once while the client is
 * being built. Labels and attachments have no v2 equivalent and go through v1 paths.</p>
 */
public class ConfluenceClientV2 extends ConfluenceClient {

    /** The attachment comment carries the content hash between two hash marks. */
    private static final String HASH_IN_COMMENT = "(?sm).*#([^#]+)#.*";

    private final String spaceId;

    public ConfluenceClientV2(ConfigService configService) {
        super(configService);
        this.spaceId = fetchSpaceIdByKey(String.valueOf(configService.getConfigProperty("confluence.spaceKey")));
    }

    ConfluenceClientV2(ConfigService configService, RestClient restClient) {
        super(configService, restClient);
        this.spaceId = fetchSpaceIdByKey(String.valueOf(configService.getConfigProperty("confluence.spaceKey")));
    }

    public String getSpaceId() {
        return spaceId;
    }

    final String fetchSpaceIdByKey(String spaceKey) {
        URI uri = uri(API_V2_PATH + "/spaces", Map.of(
                "keys", spaceKey,
                "status", "current",
                "limit", "1"), List.of("keys", "status", "limit"));
        List<?> results = listAt(callApiAndFailIfNot20x(new HttpGet(uri)), "results");
        if (results.isEmpty()) {
            return null;
        }
        Object id = ((Map<?, ?>) results.get(0)).get("id");
        return id == null ? null : String.valueOf(id);
    }

    @Override
    public Object addLabel(Object pageId, Object label) {
        // v2 has no label endpoint, so this goes through v1.
        HttpPost post = new HttpPost(API_V1_PATH + "/content/" + pageId + "/label");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        post.setEntity(new StringEntity(new JsonBuilder(List.of(label)).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(post);
    }

    @Override
    public Object getAttachment(Object pageId, Object fileName) {
        URI uri = uri(API_V2_PATH + "/pages/" + pageId + "/attachments",
                Map.of("filename", String.valueOf(fileName)), List.of("filename"));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    @Override
    public Object updateAttachment(String pageId, String attachmentId, InputStream inputStream,
                                   String fileName, String note, String localHash) {
        // v2 has no attachment upload, so this goes through v1.
        return uploadAttachment(API_V1_PATH + "/content/" + pageId + "/child/attachment/" + attachmentId + "/data",
                inputStream, fileName, note, localHash);
    }

    @Override
    public Object createAttachment(String pageId, InputStream inputStream, String fileName,
                                   String note, String localHash) {
        return uploadAttachment(API_V1_PATH + "/content/" + pageId + "/child/attachment",
                inputStream, fileName, note, localHash);
    }

    @Override
    public Object attachmentHasChanged(Object attachment, Object localHash) {
        // v2 reports the comment directly, where v1 nests it under extensions.
        List<?> results = listAt(attachment, "results");
        Object first = results.isEmpty() ? null : results.get(0);
        Object comment = first instanceof Map<?, ?> map ? map.get("comment") : null;
        String remoteHash = String.valueOf(comment).replaceAll(HASH_IN_COMMENT, "$1");
        return !Objects.equals(remoteHash, localHash);
    }

    @Override
    public Map<?, ?> fetchPagesBySpaceKey(String spaceKey, Integer pageLimit) {
        Map<String, Object> allPages = new LinkedHashMap<>();
        String cursor = null;
        boolean morePages = true;
        while (morePages) {
            Object response = callApiAndFailIfNot20x(new HttpGet(
                    pagedUri(API_V2_PATH + "/spaces/" + spaceId + "/pages", pageLimit, cursor)));
            List<?> results = listAt(response, "results");
            String next = nextCursor(response);
            if (results.isEmpty() || next == null) {
                morePages = false;
            } else {
                cursor = next;
            }
            for (Object result : results) {
                Map<?, ?> match = (Map<?, ?>) result;
                allPages.put(titleKey(match), pageEntry(match, match.get("parentId")));
            }
            reportProgress(morePages, allPages.size());
        }
        return allPages;
    }

    /**
     * Walks the page tree below the given ancestors, breadth first.
     *
     * <p>Consumes {@code pageIds}: the original implementation removed entries as it went, and
     * callers pass a list they do not reuse.</p>
     */
    @Override
    public Map<?, ?> fetchPagesByAncestorId(List<String> pageIds, Integer pageLimit) {
        Map<String, Object> allPages = new LinkedHashMap<>();
        List<Object> discovered = new ArrayList<>();
        String cursor = null;
        boolean morePages = true;
        String pageId = pageIds.remove(0);
        while (morePages) {
            Object response = callApiAndFailIfNot20x(new HttpGet(
                    pagedUri(API_V2_PATH + "/pages/" + pageId + "/children", pageLimit, cursor)));
            List<?> results = listAt(response, "results");
            for (Object result : results) {
                Map<?, ?> match = (Map<?, ?>) result;
                discovered.add(match.get("id"));
                allPages.put(titleKey(match), pageEntry(match, pageId));
            }
            String next = nextCursor(response);
            if (results.isEmpty() && discovered.isEmpty()) {
                if (pageIds.isEmpty()) {
                    morePages = false;
                } else {
                    pageId = pageIds.remove(0);
                }
            } else if (!results.isEmpty() && next != null) {
                cursor = next;
            } else {
                cursor = null;
                pageId = String.valueOf(discovered.remove(0));
            }
            reportProgress(morePages, allPages.size());
        }
        return allPages;
    }

    @Override
    public Object fetchPageByPageId(String id) {
        URI uri = uri(API_V2_PATH + "/pages/" + id, Map.of("body-format", "storage"), List.of("body-format"));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    @Override
    public Object deletePage(String id) {
        return callApiAndFailIfNot20x(new HttpDelete(uri(API_V2_PATH + "/pages/" + id, Map.of(), List.of())));
    }

    /**
     * @param spaceKey unused: v2 addresses the space by the id resolved while constructing
     */
    @Override
    protected Object fetchPageIdByName(String name, String spaceKey) {
        URI uri = uri(API_V2_PATH + "/spaces/" + spaceId + "/pages",
                Map.of("title", name, "status", "current"), List.of("title", "status"));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    /**
     * @param confluenceSpaceKey unused: v2 addresses the space by id
     */
    @Override
    public Object updatePage(String pageId, String title, String confluenceSpaceKey, Object localPage,
                             Integer pageVersion, String pageVersionComment, String parentId) {
        Map<String, Object> requestBody = pageRequestBody(title, localPage, parentId);
        requestBody.put("id", pageId);
        requestBody.put("version", versionOf(pageVersion, pageVersionComment));
        HttpPut put = new HttpPut(API_V2_PATH + "/pages/" + pageId);
        put.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        put.setEntity(new StringEntity(new JsonBuilder(requestBody).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(put);
    }

    /**
     * @param confluenceSpaceKey unused: v2 addresses the space by id
     */
    @Override
    public Object createPage(String title, String confluenceSpaceKey, Object localPage,
                             String pageVersionComment, String parentId) {
        Map<String, Object> requestBody = pageRequestBody(title, localPage, parentId);
        requestBody.put("version", versionOf(1, pageVersionComment));
        HttpPost post = new HttpPost(API_V2_PATH + "/pages");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        post.setEntity(new StringEntity(new JsonBuilder(requestBody).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(post);
    }

    private Map<String, Object> pageRequestBody(String title, Object localPage, String parentId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("title", title);
        // editor and content-appearance are Cloud concepts, which is where v2 lives.
        requestBody.put("metadata", Map.of("properties", Map.of(
                "editor", Map.of("value", editorVersion),
                "content-appearance-draft", Map.of("value", "full-width"),
                "content-appearance-published", Map.of("value", "full-width"))));
        requestBody.put("status", "current");
        requestBody.put("spaceId", spaceId);
        requestBody.put("parentId", parentId == null || parentId.isEmpty() ? "" : parentId);
        requestBody.put("body", Map.of("value", localPage, "representation", "storage"));
        return requestBody;
    }

    private static Map<String, Object> versionOf(Integer number, String message) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("number", number);
        version.put("message", message);
        return version;
    }

    private static URI pagedUri(String path, Integer pageLimit, String cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("depth", "all");
        query.put("limit", pageLimit.toString());
        List<String> order = new ArrayList<>(List.of("depth", "limit"));
        if (cursor != null && !cursor.isEmpty()) {
            query.put("cursor", cursor);
            order.add("cursor");
        }
        return uri(path, query, order);
    }

    /**
     * @return the cursor of the next page, or {@code null} when the response carries no next link
     */
    private static String nextCursor(Object response) {
        Object links = response instanceof Map<?, ?> map ? map.get("_links") : null;
        Object next = links instanceof Map<?, ?> map ? map.get("next") : null;
        if (next == null) {
            return null;
        }
        try {
            for (NameValuePair parameter : new URIBuilder(String.valueOf(next)).getQueryParams()) {
                if ("cursor".equals(parameter.getName())) {
                    return parameter.getValue();
                }
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Confluence returned an unparseable next link: " + next, e);
        }
        return null;
    }

    private static void reportProgress(boolean morePages, int fetched) {
        System.out.println(morePages
                ? "Fetched " + fetched + " pages, fetching next chunk..."
                : "Fetched all pages:");
    }

    private static URI uri(String path, Map<String, String> query, List<String> order) {
        try {
            URIBuilder builder = new URIBuilder(path);
            for (String name : order) {
                builder.addParameter(name, query.get(name));
            }
            return builder.build();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot build a request URI for '" + path + "'", e);
        }
    }

    private static String titleKey(Map<?, ?> match) {
        return String.valueOf(match.get("title")).toLowerCase();
    }

    /** Confluence page titles are unique within a space, so the title serves as the key. */
    private static Map<String, Object> pageEntry(Map<?, ?> match, Object parentId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("title", match.get("title"));
        entry.put("id", match.get("id"));
        entry.put("parentId", parentId);
        return entry;
    }

    private static List<?> listAt(Object container, String key) {
        Object value = container instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof List<?> list ? list : List.of();
    }
}
