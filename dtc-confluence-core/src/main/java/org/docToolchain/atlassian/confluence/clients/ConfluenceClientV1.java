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
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.configuration.ConfigService;

/**
 * Speaks the Confluence REST API v1, which is what Server and Data Center offer, and what Cloud
 * still accepts.
 */
public class ConfluenceClientV1 extends ConfluenceClient {

    /** The attachment comment carries the content hash between two hash marks. */
    private static final String HASH_IN_COMMENT = "(?sm).*#([^#]+)#.*";

    public ConfluenceClientV1(ConfigService configService) {
        super(configService);
    }

    /**
     * Takes the REST client from outside, for callers that need to supply their own.
     */
    public ConfluenceClientV1(ConfigService configService, RestClient restClient) {
        super(configService, restClient);
    }

    @Override
    public Object addLabel(Object pageId, Object label) {
        HttpPost post = new HttpPost(API_V1_PATH + "/content/" + pageId + "/label");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        post.setEntity(new StringEntity(new JsonBuilder(List.of(label)).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(post);
    }

    @Override
    public Object getAttachment(Object pageId, Object fileName) {
        URI uri = uri(API_V1_PATH + "/content/" + pageId + "/child/attachment",
                List.of(new BasicNameValuePair("filename", String.valueOf(fileName))));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    @Override
    public Object updateAttachment(String pageId, String attachmentId, InputStream inputStream,
                                   String fileName, String note, String localHash) {
        String uri = API_V1_PATH + "/content/" + pageId + "/child/attachment/" + attachmentId + "/data";
        return uploadAttachment(uri, inputStream, fileName, note, localHash);
    }

    @Override
    public Object createAttachment(String pageId, InputStream inputStream, String fileName,
                                   String note, String localHash) {
        String uri = API_V1_PATH + "/content/" + pageId + "/child/attachment";
        System.out.println("Uploading attachment to " + uri);
        return uploadAttachment(uri, inputStream, fileName, note, localHash);
    }

    @Override
    public Object attachmentHasChanged(Object attachment, Object localHash) {
        List<?> results = listAt(attachment, "results");
        Map<?, ?> extensions = mapAt(results.isEmpty() ? null : results.get(0), "extensions");
        String remoteHash = String.valueOf(extensions.get("comment")).replaceAll(HASH_IN_COMMENT, "$1");
        return !Objects.equals(remoteHash, localHash);
    }

    @Override
    public Map<?, ?> fetchPagesBySpaceKey(String spaceKey, Integer pageLimit) {
        Map<String, Object> allPages = new LinkedHashMap<>();
        boolean morePages = true;
        int offset = 0;
        while (morePages) {
            URI uri = uri(API_V1_PATH + "/content", List.of(
                    new BasicNameValuePair("type", "page"),
                    new BasicNameValuePair("spaceKey", spaceKey),
                    new BasicNameValuePair("expand", "ancestors"),
                    new BasicNameValuePair("limit", pageLimit.toString()),
                    new BasicNameValuePair("start", Integer.toString(offset))));
            List<?> results = resultsOf(callApiAndFailIfNot20x(new HttpGet(uri)));
            if (results.isEmpty()) {
                morePages = false;
            } else {
                offset += results.size();
            }
            for (Object result : results) {
                Map<?, ?> match = (Map<?, ?>) result;
                List<?> ancestors = listAt(match, "ancestors");
                Object parentId = ancestors.isEmpty()
                        ? null
                        : ((Map<?, ?>) ancestors.get(ancestors.size() - 1)).get("id");
                allPages.put(titleKey(match), pageEntry(match, parentId));
            }
            System.out.println(morePages
                    ? "Fetched " + offset + " pages, fetching next chunk..."
                    : "Fetched all pages:");
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
        int offset = 0;
        String pageId = pageIds.remove(0);
        boolean morePages = true;
        while (morePages) {
            URI uri = uri(API_V1_PATH + "/content/" + pageId + "/child/page", List.of(
                    new BasicNameValuePair("type", "page"),
                    new BasicNameValuePair("limit", pageLimit.toString()),
                    new BasicNameValuePair("start", Integer.toString(offset))));
            List<?> results = resultsOf(callApiAndFailIfNot20x(new HttpGet(uri)));
            for (Object result : results) {
                Map<?, ?> match = (Map<?, ?>) result;
                discovered.add(match.get("id"));
                allPages.put(titleKey(match), pageEntry(match, pageId));
            }
            if (results.isEmpty() && discovered.isEmpty()) {
                if (pageIds.isEmpty()) {
                    morePages = false;
                } else {
                    pageId = pageIds.remove(0);
                }
            } else if (!results.isEmpty()) {
                offset += results.size();
            } else {
                offset = 0;
                pageId = String.valueOf(discovered.remove(0));
            }
            System.out.println(morePages
                    ? "Fetched " + offset + " pages, fetching next chunk..."
                    : "Fetched all pages:");
        }
        return allPages;
    }

    @Override
    public Object fetchPageByPageId(String id) {
        URI uri = uri(API_V1_PATH + "/content/" + id,
                List.of(new BasicNameValuePair("expand", "body.storage,version,ancestors")));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    @Override
    public Object deletePage(String id) {
        return callApiAndFailIfNot20x(new HttpDelete(uri(API_V1_PATH + "/content/" + id, List.of())));
    }

    @Override
    protected Object fetchPageIdByName(String name, String spaceKey) {
        URI uri = uri(API_V1_PATH + "/content", List.of(
                new BasicNameValuePair("title", name),
                new BasicNameValuePair("spaceKey", spaceKey)));
        return callApiAndReturnOrNull(new HttpGet(uri));
    }

    @Override
    public Object updatePage(String pageId, String title, String confluenceSpaceKey, Object localPage,
                             Integer pageVersion, String pageVersionComment, String parentId) {
        Map<String, Object> requestBody = getDefaultModifyPageRequestBody(title, confluenceSpaceKey, localPage, parentId);
        requestBody.put("id", pageId);
        requestBody.put("version", Map.of("number", pageVersion, "message", orEmpty(pageVersionComment)));
        HttpPut put = new HttpPut(API_V1_PATH + "/content/" + pageId);
        put.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        put.setEntity(new StringEntity(new JsonBuilder(requestBody).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(put);
    }

    @Override
    public Object createPage(String title, String confluenceSpaceKey, Object localPage,
                             String pageVersionComment, String parentId) {
        Map<String, Object> requestBody = getDefaultModifyPageRequestBody(title, confluenceSpaceKey, localPage, parentId);
        requestBody.put("version", Map.of("message", orEmpty(pageVersionComment)));
        HttpPost post = new HttpPost(API_V1_PATH + "/content");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON);
        post.setEntity(new StringEntity(new JsonBuilder(requestBody).toPrettyString(), StandardCharsets.UTF_8));
        return callApiAndFailIfNot20x(post);
    }

    protected Map<String, Object> getDefaultModifyPageRequestBody(String title, String confluenceSpaceKey,
                                                                 Object localPage, String parentId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("type", "page");
        requestBody.put("title", title);
        // editor and content-appearance are Cloud concepts. Data Center discards them - measured
        // against ASF Confluence 9.2.21, where a created page carried no content properties at all.
        requestBody.put("metadata", Map.of("properties", Map.of(
                "editor", Map.of("value", editorVersion),
                "content-appearance-draft", Map.of("value", "full-width"),
                "content-appearance-published", Map.of("value", "full-width"))));
        requestBody.put("space", Map.of("key", confluenceSpaceKey));
        requestBody.put("body", Map.of("storage", Map.of(
                "value", localPage,
                "representation", "storage")));
        if (parentId != null && !parentId.isEmpty()) {
            requestBody.put("ancestors", List.of(Map.of("type", "page", "id", parentId)));
        }
        return requestBody;
    }

    private static URI uri(String path, List<BasicNameValuePair> query) {
        try {
            return new URIBuilder(path).addParameters(List.copyOf(query)).build();
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

    private static List<?> resultsOf(Object response) {
        return listAt(response, "results");
    }

    private static List<?> listAt(Object container, String key) {
        Object value = container instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<?, ?> mapAt(Object container, String key) {
        Object value = container instanceof Map<?, ?> map ? map.get(key) : null;
        return value instanceof Map<?, ?> nested ? nested : Map.of();
    }

    private static String orEmpty(String value) {
        return value == null || value.isEmpty() ? "" : value;
    }
}
