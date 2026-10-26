package org.docToolchain.atlassian.confluence.clients;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.configuration.ConfigService;
import org.docToolchain.http.NonJsonResponseException;

/**
 * Common ground for the two Confluence REST APIs: works out where the API lives, carries the
 * credentials check, and uploads attachments the same way for both.
 */
public abstract class ConfluenceClient {

    /** Cloud serves the API under /wiki unless the configuration says otherwise. */
    private static final String API_DEFAULT_CONTEXT = "wiki";

    private static final String API_V1_IDENTIFIER = "/rest/api";
    private static final String API_V2_IDENTIFIER = "/api/v2";

    /** What the credentials check asks: the endpoint that reports who they belong to. */
    private static final String CURRENT_USER = "/user/current";

    /**
     * The content hash an upload of ours leaves in the version comment, between two hash marks.
     *
     * <p>An MD5 in hex, and nothing else. Any hash-delimited text used to count: a comment
     * reading "Manually revised; see #123#" then carried a "hash" of "123". Never equal to a
     * local hash, so it did not make an attachment look unchanged - but it did make the revision
     * look like one this publisher wrote, which let the publisher write over it.</p>
     */
    private static final Pattern HASH_IN_COMMENT = Pattern.compile("#([0-9a-f]{32})#");

    /** What a version number of 0 means: the answer carried none. Confluence counts from 1. */
    private static final int NO_VERSION_REPORTED = 0;

    /**
     * How many of a page's attachments to ask for at once.
     *
     * <p>More than any documentation page has met, and the most either API serves in one answer.
     * A page holding more than this is read short, and a name that is then not among them is
     * published as the document refers to it - which is what every run did before the names were
     * matched at all.</p>
     */
    protected static final int ATTACHMENT_LIMIT = 200;

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

    /**
     * Where Confluence is served, as a URL a browser can follow: scheme, host, the port where the
     * configuration names one, and the context path.
     *
     * <p>What a link into Confluence is built from, for an export that meets a link to a page it
     * did not export itself. The path is the one {@link #contextPathFor} derives rather than
     * whatever was configured, because that value ranges from a bare host to a full API
     * endpoint.</p>
     *
     * @return the base URL, without a trailing slash, or empty where the configuration names no
     *         host at all - a URL built without one would point nowhere
     */
    public static String baseUrlFor(ConfigService configService) {
        String apiConfigItem = String.valueOf(configService.getConfigProperty("confluence.api"));
        try {
            URIBuilder configured = new URIBuilder(apiConfigItem);
            if (configured.getScheme() == null || configured.getHost() == null) {
                return "";
            }
            String port = configured.getPort() < 0 ? "" : ":" + configured.getPort();
            return configured.getScheme() + "://" + configured.getHost() + port
                    + contextPathFor(configService);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "confluence.api is not a valid URI: '" + apiConfigItem + "'", e);
        }
    }

    protected final String API_V1_PATH;
    protected final String API_V2_PATH;

    /**
     * The REST v1 endpoint as a URL a reader can compare against the address bar of a browser.
     *
     * <p>Kept because {@link #verifyCredentials()} prints it, and because the request itself
     * carries only {@link #API_V1_PATH}: the host lives in the REST client, so the output of a
     * failing check named no address at all.</p>
     */
    private final String apiV1Url;

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
        // baseUrlFor already ends with the derived context path, which is the half of
        // apiV1PathFor that is not the identifier - appending the whole path would repeat it.
        this.apiV1Url = baseUrlFor(configService) + API_V1_IDENTIFIER;
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
     * Confirms that the configured credentials resolve to a real user, and says where it looked.
     *
     * <p>Confluence Data Center answers {@code /user/current} with HTTP 200 and a body of
     * {@code {"type":"anonymous"}} when the credentials are invalid, rather than 401. Checking the
     * status code alone therefore reports success for any token.</p>
     *
     * <p>The address is printed before the request, and an answer that is not an API answer is
     * told apart from a rejected user, because three different faults were indistinguishable in
     * the output of this check: a wrong address, a wrong token, and something in front of
     * Confluence answering in its place. Measured against a customer instance, a request that
     * landed on an SSO login page was reported as "the API resolved to an anonymous user", which
     * sent the reader to the token while the request had never reached a REST endpoint. The
     * address has to be the derived one: {@code confluence.api} takes anything from a bare host
     * to a full endpoint, and a bare host is given Cloud's {@code /wiki}, so the configured value
     * does not say where the call goes.</p>
     */
    public Object verifyCredentials() {
        String endpoint = apiV1Url + CURRENT_USER;
        System.out.println("Verifying " + endpoint + " (derived from confluence.api)");
        Object response;
        try {
            response = callApiAndFailIfNot20x(new HttpGet(API_V1_PATH + CURRENT_USER));
        } catch (NonJsonResponseException notAnApi) {
            throw didNotAnswerAsAnApi(endpoint, notAnApi.getMessage(), notAnApi);
        }
        if (response == null) {
            throw didNotAnswerAsAnApi(endpoint, "the endpoint answered without a body", null);
        }
        if (!(response instanceof Map<?, ?> user)) {
            throw didNotAnswerAsAnApi(endpoint, "the endpoint answered JSON, but not a user", null);
        }
        if ("anonymous".equals(user.get("type"))) {
            throw new IllegalStateException(
                    "Confluence did not accept the credentials: the API resolved to an anonymous user. "
                            + "Check confluence.bearerToken (Data Center: personal access token) "
                            + "or confluence.credentials.");
        }
        Object identity = identityOf(user);
        if (identity == null) {
            throw new IllegalStateException(
                    "Confluence answered a user this check cannot confirm: it carries neither a "
                            + "username, nor an accountId, nor a userKey.");
        }
        System.out.println("Authenticated as '" + identity + "' (" + user.get("displayName") + ")");
        return user;
    }

    /**
     * Says that whatever answered was not the API, and names the two things that cause it.
     *
     * <p>Kept apart from the credentials message on purpose: an answer that is not an API answer
     * says nothing whatsoever about the credentials.</p>
     *
     * @param whatCameBack what did answer, as a sentence fragment - the status and the declared
     *                     type are the evidence, and no part of the configuration is printed here
     */
    private static IllegalStateException didNotAnswerAsAnApi(String endpoint, String whatCameBack,
                                                             Exception cause) {
        return new IllegalStateException("Confluence did not answer as an API at '" + endpoint
                + "': " + whatCameBack + "."
                + "\nEither confluence.api names the wrong context path, or something in front of "
                + "Confluence - an SSO proxy, for instance - answered in its place."
                + "\nThe credentials are not implicated: the request did not reach a REST endpoint.",
                cause);
    }

    /**
     * What the answer calls the user it resolved to.
     *
     * <p>Data Center answers with a {@code username}; Cloud stopped doing so and answers an
     * {@code accountId} with a {@code publicName}. Required to see a username, the check refused
     * every Cloud instance - with credentials that work. {@code userKey} is what older Data
     * Center versions answer where the user has no name of its own.</p>
     *
     * @return the first of those the answer carries, or {@code null} where it carries none
     */
    private static Object identityOf(Map<?, ?> user) {
        for (String key : List.of("username", "accountId", "userKey")) {
            Object value = user.get(key);
            if (value != null && !String.valueOf(value).isEmpty()) {
                return value;
            }
        }
        return null;
    }

    public abstract Object addLabel(Object pageId, Object label);

    public abstract Object getAttachment(Object pageId, Object filename);

    /** @return every attachment of the page, as the API answers it */
    public abstract Object getAttachments(Object pageId);

    /**
     * The names Confluence knows this page's attachments under, which is what a reference the
     * export sanitised is matched against. A lookup by name cannot answer it: the name being
     * matched is the one that is already wrong.
     *
     * @return the names, in the order the answer listed them, and none where it listed none
     */
    public List<String> attachmentNames(Object pageId) {
        return namesIn(getAttachments(pageId));
    }

    public abstract Object updateAttachment(String pageId, String attachmentId, InputStream inputStream,
                                            String fileName, String note, String localHash);

    public abstract Object createAttachment(String pageId, InputStream inputStream, String fileName,
                                            String note, String localHash);

    /**
     * @param response what {@link #getAttachment} answered
     * @return the attachment it found, or {@code null} where it found none
     */
    public abstract RemoteAttachment attachmentOf(Object response);

    /**
     * @return the hash a comment of ours carries, or empty where the comment is not one of ours -
     *         which is the answer for a comment another writer left, such as the draw.io app's
     *         "draw.io diagram", and for one that merely has hash marks in it
     *
     *     <p>Read with {@code find} rather than with a {@code replaceAll} over the whole string.
     *     A {@code replaceAll} that matches nothing answers the subject unchanged, so a foreign
     *     comment came back as the "remote hash" itself: never equal to any local hash, and
     *     therefore read as a change of ours to overwrite.</p>
     *
     *     <p>Not anchored to the end of the comment on purpose: the note in front of the hash is
     *     configurable, and what Confluence does to whitespace in a version comment is not
     *     something measured here. The shape of the payload is what tells an upload of ours from
     *     a sentence that happens to carry a number between hash marks.</p>
     */
    public static String hashIn(Object comment) {
        Matcher matcher = HASH_IN_COMMENT.matcher(comment == null ? "" : String.valueOf(comment));
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * @return the first attachment of a lookup's answer, or {@code null} where it found none
     */
    protected static Map<?, ?> firstAttachmentIn(Object response) {
        Object results = response instanceof Map<?, ?> map ? map.get("results") : null;
        if (results instanceof List<?> found && !found.isEmpty()
                && found.get(0) instanceof Map<?, ?> first) {
            return first;
        }
        return null;
    }

    /**
     * @return the names the answer lists, which both APIs call an attachment's title
     */
    protected static List<String> namesIn(Object response) {
        Object results = response instanceof Map<?, ?> map ? map.get("results") : null;
        if (!(results instanceof List<?> found)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object entry : found) {
            Object title = entry instanceof Map<?, ?> attachment ? attachment.get("title") : null;
            if (title != null) {
                names.add(String.valueOf(title));
            }
        }
        return names;
    }

    /**
     * @return the revision the answer reports, or 0 where it reports none - v1 answers an
     *         attachment lookup without a version unless the request asks for one, and a guess
     *         would be worse than saying nothing
     */
    protected static int versionNumberOf(Map<?, ?> attachment) {
        Object version = attachment.get("version");
        Object number = version instanceof Map<?, ?> map ? map.get("number") : null;
        if (number instanceof Number counted) {
            return counted.intValue();
        }
        try {
            return number == null ? NO_VERSION_REPORTED : Integer.parseInt(String.valueOf(number));
        } catch (NumberFormatException notANumber) {
            return NO_VERSION_REPORTED;
        }
    }

    /**
     * The hash travels in the upload comment, because Confluence offers nowhere else to put it and
     * it is what later tells an unchanged attachment from a changed one, and an attachment this
     * publisher wrote from one somebody else wrote.
     */
    protected Object uploadAttachment(Object uri, InputStream inputStream, String fileName,
                                      Object note, Object localHash) {
        HttpPost post = new HttpPost(String.valueOf(uri));
        String boundary = "dtcConfluence" + UUID.randomUUID();
        post.setEntity(multipart(inputStream, fileName, note, localHash, boundary));
        // Written here rather than left to the entity, which declares ISO-8859-1 whatever the
        // builder is told - see multipart below. Confluence believes the declaration.
        post.setHeader(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        return callApiAndFailIfNot20x(post);
    }

    /**
     * The body of an attachment upload.
     *
     * <p>Confluence takes the file name it is sent literally, so the name on the wire has to be
     * the name. What the three multipart modes write for it, measured:</p>
     *
     * <pre>
     *                      my image.png     a name with an umlaut
     *   EXTENDED           my%20image.png   %C3%9Cbersicht.png
     *   STRICT             my image.png     one byte per character, so not UTF-8
     *   LEGACY + UTF-8     my image.png     the name itself
     * </pre>
     *
     * <p>Hence LEGACY with an explicit UTF-8 charset. That alone is not enough: the entity then
     * still declares {@code multipart/form-data; charset=ISO-8859-1}, and neither
     * {@code setContentType} nor anything else on the builder changes it, so Confluence read the
     * correct UTF-8 bytes as Latin-1 and stored a name beginning with an A-tilde. The caller
     * therefore fixes the boundary and writes the request's content type itself, without a
     * charset - which is what a browser sends, and what Confluence reads as UTF-8.</p>
     *
     * <p>Measured on Data Center 9.2.21. Before this, an attachment went up as
     * {@code %C3%9Cbersicht.png} while the page body referred to the name with the umlaut, so the
     * page pointed at an attachment that does not exist and the picture did not render. It was
     * invisible for as long as every name reaching here had been through the export's
     * sanitisation, which replaces exactly the characters that were being encoded.</p>
     *
     * <p>The comment part keeps its own content type: it carries a fixed note and a hex hash.</p>
     */
    static HttpEntity multipart(InputStream inputStream, String fileName, Object note,
                                Object localHash, String boundary) {
        return MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.LEGACY)
                .setCharset(StandardCharsets.UTF_8)
                .setBoundary(boundary)
                .addPart("file", new InputStreamBody(inputStream, fileName))
                .addPart("comment", new StringBody(note + "\r\n#" + localHash + "#", ContentType.TEXT_PLAIN))
                .build();
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

    /**
     * @return the id of the page with that title in that space, or {@code null} where there is
     *         none
     *
     *     <p>The extraction of the id from the search result belongs here rather than in the
     *     callers: the method is named after an id, and when each caller did it for itself one of
     *     them did not - the export passed the whole response body through
     *     {@code String.valueOf} and asked Confluence for a page whose id was a printed map.</p>
     */
    public Object retrievePageIdByName(String name, String spaceKey) {
        Object response = fetchPageIdByName(name, spaceKey);
        if (!(response instanceof Map<?, ?> found)) {
            return null;
        }
        Object results = found.get("results");
        if (results instanceof List<?> pages && !pages.isEmpty()
                && pages.get(0) instanceof Map<?, ?> first) {
            return first.get("id");
        }
        return null;
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
