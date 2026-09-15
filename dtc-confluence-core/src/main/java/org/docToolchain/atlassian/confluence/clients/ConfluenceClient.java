package org.docToolchain.atlassian.confluence.clients;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.configuration.ConfigService;

/**
 * Common ground for the two Confluence REST APIs: works out where the API lives, carries the
 * credentials check, and uploads attachments the same way for both.
 */
public abstract class ConfluenceClient {

    /** Cloud serves the API under /wiki unless the configuration says otherwise. */
    private static final String API_DEFAULT_CONTEXT = "wiki";

    private static final String API_V1_IDENTIFIER = "/rest/api";
    private static final String API_V2_IDENTIFIER = "/api/v2";

    /** The path a REST v1 call hangs under, context included. */
    public static String apiV1PathFor(ConfigService configService) {
        return contextPathFor(configService) + API_V1_IDENTIFIER;
    }

    /**
     * The path Confluence itself is served under - "/confluence" on a typical Data Center, empty
     * where it sits at the root. Links Confluence hands out, such as an attachment's download
     * link, are relative to it.
     */
    public static String contextPathFor(ConfigService configService) {
        return constructApiContext(String.valueOf(configService.getConfigProperty("confluence.api")));
    }

    protected final String API_V1_PATH;
    protected final String API_V2_PATH;
    protected final String editorVersion;
    protected RestClient restClient;

    protected ConfluenceClient(ConfigService configService) {
        this(configService, new RestClient(configService));
    }

    /**
     * Takes the REST client from outside, so a test can supply one without having to intercept
     * object construction.
     */
    protected ConfluenceClient(ConfigService configService, RestClient restClient) {
        this.restClient = restClient;
        String apiConfigItem = String.valueOf(configService.getConfigProperty("confluence.api"));
        String apiContext = constructApiContext(apiConfigItem);
        this.API_V1_PATH = apiContext + API_V1_IDENTIFIER;
        this.API_V2_PATH = apiContext + API_V2_IDENTIFIER;
        this.editorVersion = determineEditorVersion(configService);
    }

    private static String constructApiContext(String configItem) {
        try {
            String apiContext = determineApiContext(new URIBuilder(configItem).getPath());
            return apiContext.isEmpty() ? "" : "/" + apiContext;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("confluence.api is not a valid URI: '" + configItem + "'", e);
        }
    }

    /**
     * Derives the context path from whatever the user configured, which ranges from a bare host to
     * a full API endpoint.
     */
    private static String determineApiContext(String apiPath) {
        // no path at all, or just a slash
        if (apiPath == null || apiPath.length() <= 1) {
            return API_DEFAULT_CONTEXT;
        }
        String remainder = apiPath
                .substring(1)
                .replace(API_V1_IDENTIFIER, "")
                .replace(API_V2_IDENTIFIER, "");
        String[] pathParts = remainder.split("/");
        if (pathParts.length == 1) {
            return pathParts[0];
        }
        // Assume the context was left out on purpose, as with
        // https://docs.atlassian.com/ConfluenceServer/rest/8.6.1/
        return "";
    }

    /**
     * Confirms that the configured credentials resolve to a real user.
     *
     * <p>Confluence Data Center answers {@code /user/current} with HTTP 200 and a body of
     * {@code {"type":"anonymous"}} when the credentials are invalid, rather than 401. Checking the
     * status code alone therefore reports success for any token.</p>
     */
    public Object verifyCredentials() {
        Object response = callApiAndFailIfNot20x(new HttpGet(API_V1_PATH + "/user/current"));
        Map<?, ?> user = response instanceof Map<?, ?> map ? map : null;
        if (user == null || "anonymous".equals(user.get("type")) || user.get("username") == null) {
            throw new IllegalStateException(
                    "Confluence did not accept the credentials: the API resolved to an anonymous user. "
                            + "Check confluence.bearerToken (Data Center: personal access token) "
                            + "or confluence.credentials.");
        }
        System.out.println("Authenticated as '" + user.get("username") + "' (" + user.get("displayName") + ")");
        return user;
    }

    public abstract Object addLabel(Object pageId, Object label);

    public abstract Object getAttachment(Object pageId, Object filename);

    public abstract Object updateAttachment(String pageId, String attachmentId, InputStream inputStream,
                                            String fileName, String note, String localHash);

    public abstract Object createAttachment(String pageId, InputStream inputStream, String fileName,
                                            String note, String localHash);

    public abstract Object attachmentHasChanged(Object attachment, Object localHash);

    /**
     * The hash travels in the upload comment, because Confluence offers nowhere else to put it and
     * it is what later tells an unchanged attachment from a changed one.
     */
    protected Object uploadAttachment(Object uri, InputStream inputStream, String fileName,
                                      Object note, Object localHash) {
        HttpPost post = new HttpPost(String.valueOf(uri));
        HttpEntity entity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.EXTENDED)
                .addPart("file", new InputStreamBody(inputStream, fileName))
                .addPart("comment", new StringBody(note + "\r\n#" + localHash + "#", ContentType.TEXT_PLAIN))
                .build();
        post.setEntity(entity);
        return callApiAndFailIfNot20x(post);
    }

    protected Object callApiAndFailIfNot20x(ClassicHttpRequest httpRequest) {
        return restClient.doRequestAndFailIfNot20x(httpRequest);
    }

    protected Object callApiAndReturnOrNull(ClassicHttpRequest httpRequest) {
        return restClient.doRequestAndReturnOrNull(httpRequest);
    }

    public abstract Map<?, ?> fetchPagesBySpaceKey(String spaceKey, Integer pageLimit);

    public abstract Map<?, ?> fetchPagesByAncestorId(java.util.List<String> pageIds, Integer pageLimit);

    public abstract Object fetchPageByPageId(String id);

    public abstract Object deletePage(String id);

    public abstract Object updatePage(String pageId, String title, String confluenceSpaceKey, Object localPage,
                                      Integer pageVersion, String pageVersionComment, String parentId);

    public abstract Object createPage(String title, String confluenceSpaceKey, Object localPage,
                                      String pageVersionComment, String parentId);

    protected abstract Object fetchPageIdByName(String name, String spaceKey);

    public Object retrieveFullPageById(String pageId) {
        Object page = fetchPageByPageId(pageId);
        return page == null ? Map.of() : page;
    }

    public Object retrievePageIdByName(String name, String spaceKey) {
        return fetchPageIdByName(name, spaceKey);
    }

    private static String determineEditorVersion(ConfigService configService) {
        Object enforceNewEditor = configService.getConfigProperty("confluence.enforceNewEditor");
        if (enforceNewEditor != null && Boolean.parseBoolean(String.valueOf(enforceNewEditor))) {
            System.out.println("WARNING: You are using the new editor version v2. "
                    + "This is not yet fully supported by docToolchain.");
            return "v2";
        }
        return "v1";
    }
}
