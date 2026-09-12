package org.docToolchain.atlassian.confluence.clients;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.util.concurrent.RateLimiter;
import groovy.json.JsonSlurper;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.net.URIBuilder;
import org.docToolchain.configuration.ConfigService;
import org.docToolchain.http.BasicRestClient;
import org.docToolchain.http.RequestFailedException;

/**
 * Talks to the Confluence REST API: applies the configured credentials, proxy and rate limit,
 * and parses successful responses as JSON.
 */
public class RestClient extends BasicRestClient {

    /** Confluence throttles aggressively, so stay well below whatever it would allow. */
    private static final double DEFAULT_RATE_LIMIT = 10;

    private static final String DEFAULT_PROXY_SCHEME = "http";

    private final ConfigService configService;
    private RateLimiter rateLimiter;
    protected HttpHost targetHost;
    protected HttpHost proxyHost;
    protected Set<Header> headers;

    public RestClient(ConfigService configService) {
        super();
        this.configService = configService;
        initialize();
    }

    /**
     * Decides what to do with a response the server did not answer with 2xx: either raise, or
     * signal that the caller should be handed {@code null}.
     */
    @FunctionalInterface
    private interface RejectionPolicy {
        /**
         * @return {@code true} if the request should yield {@code null} rather than a value
         */
        boolean tolerate(ClassicHttpResponse response) throws IOException;
    }

    public Object doRequestAndFailIfNot20x(ClassicHttpRequest httpRequest) {
        return doRequest(httpRequest, response -> {
            throw new RequestFailedException(response, null);
        });
    }

    /**
     * Answers {@code null} when Confluence says the resource is not there, and raises when it says
     * it could not answer. Callers read {@code null} as "this page does not exist yet" and go on to
     * create it, so a server error must not arrive as {@code null}.
     */
    public Object doRequestAndReturnOrNull(ClassicHttpRequest httpRequest) {
        return doRequest(httpRequest, response -> {
            if (response.getCode() >= HttpStatus.SC_INTERNAL_SERVER_ERROR) {
                throw new RequestFailedException(response, null);
            }
            System.out.println("Got status code " + response.getCode());
            return true;
        });
    }

    private static boolean isNotSuccessful(ClassicHttpResponse response) {
        return response.getCode() < HttpStatus.SC_OK || response.getCode() > HttpStatus.SC_PARTIAL_CONTENT;
    }

    private Object doRequest(ClassicHttpRequest httpRequest, RejectionPolicy onRejection) {
        rateLimiter.acquire();
        return doRequest(targetHost, httpRequest, new RestClientResponseHandler(onRejection))
                .map(response -> (Object) new JsonSlurper().parseText(response))
                .orElse(null);
    }

    private void initialize() {
        this.rateLimiter = RateLimiter.create(configuredRateLimit());
        this.headers = constructDefaultHeaders();
        this.targetHost = constructTargetHost();
        if (!configService.getFlatConfigSubTree("confluence.proxy").isEmpty()) {
            configureProxy();
        }
        httpClientBuilder.setDefaultHeaders(headers);
    }

    private double configuredRateLimit() {
        Object configured = configService.getConfigProperty("confluence.rateLimit");
        return configured == null ? DEFAULT_RATE_LIMIT : Double.parseDouble(String.valueOf(configured));
    }

    private Set<Header> constructDefaultHeaders() {
        Set<Header> defaultHeaders = new HashSet<>();
        defaultHeaders.add(new BasicHeader("X-Atlassian-Token", "no-check"));
        Object bearerToken = configService.getConfigProperty("confluence.bearerToken");
        if (bearerToken != null) {
            defaultHeaders.add(new BasicHeader("Authorization", "Bearer " + bearerToken));
            System.out.println("Start using bearer auth");
        } else {
            defaultHeaders.add(new BasicHeader("Authorization",
                    "Basic " + configService.getConfigProperty("confluence.credentials")));
            // Some installations put an API gateway in front of Confluence that wants its own key.
            Object apiKey = configService.getConfigProperty("confluence.apikey");
            if (apiKey != null) {
                defaultHeaders.add(new BasicHeader("keyid", String.valueOf(apiKey)));
            }
        }
        return defaultHeaders;
    }

    private void configureProxy() {
        Map<String, Object> proxy = configService.getFlatConfigSubTree("confluence.proxy");
        Object scheme = proxy.get("schema");
        this.proxyHost = new HttpHost(
                scheme == null ? DEFAULT_PROXY_SCHEME : String.valueOf(scheme),
                String.valueOf(proxy.get("host")),
                Integer.parseInt(String.valueOf(proxy.get("port"))));
        httpClientBuilder.setProxy(proxyHost);
    }

    private HttpHost constructTargetHost() {
        String apiConfigItem = String.valueOf(configService.getConfigProperty("confluence.api"));
        try {
            URIBuilder builder = new URIBuilder(apiConfigItem);
            return new HttpHost(builder.getScheme(), builder.getHost(), builder.getPort());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "confluence.api is not a valid URI: '" + apiConfigItem + "'", e);
        }
    }

    public HttpHost getTargetHost() {
        return targetHost;
    }

    public Set<Header> getHeaders() {
        return headers;
    }

    public HttpHost getProxyHost() {
        return proxyHost;
    }

    @Contract(threading = ThreadingBehavior.STATELESS)
    private static class RestClientResponseHandler implements HttpClientResponseHandler<String> {

        private final RejectionPolicy onRejection;

        RestClientResponseHandler(RejectionPolicy onRejection) {
            this.onRejection = onRejection;
        }

        @Override
        public String handleResponse(ClassicHttpResponse response) throws IOException {
            HttpEntity entity = response.getEntity();
            if (isNotSuccessful(response)) {
                // Consume once, here, and stop. Reading the entity afterwards is what used to
                // turn every rejected request into a StreamClosedException.
                EntityUtils.consume(entity);
                onRejection.tolerate(response);
                return null;
            }
            return entity == null ? null : readEntity(entity);
        }

        private static String readEntity(HttpEntity entity) throws IOException {
            try {
                return EntityUtils.toString(entity);
            } catch (ParseException e) {
                throw new ClientProtocolException(e);
            } finally {
                EntityUtils.consumeQuietly(entity);
            }
        }
    }
}
