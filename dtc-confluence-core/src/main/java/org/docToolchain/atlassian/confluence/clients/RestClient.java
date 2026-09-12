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
     * Decides whether a response is acceptable. Implementations consume the entity when they
     * reject it, because nothing downstream will read it.
     */
    @FunctionalInterface
    private interface ResponseCheck {
        void check(ClassicHttpResponse response, HttpEntity entity) throws IOException;
    }

    public Object doRequestAndFailIfNot20x(ClassicHttpRequest httpRequest) {
        return doRequest(httpRequest, (response, entity) -> {
            if (isNotSuccessful(response)) {
                EntityUtils.consume(entity);
                throw new RequestFailedException(response, null);
            }
        });
    }

    public Object doRequestAndReturnOrNull(ClassicHttpRequest httpRequest) {
        return doRequest(httpRequest, (response, entity) -> {
            if (isNotSuccessful(response)) {
                EntityUtils.consume(entity);
                System.out.println("Got status code " + response.getCode());
            }
            // The Groovy original had a second branch here, meaning to throw on 5xx. It was
            // unreachable: the check above already covers every code above 206. Kept as it
            // behaves, not as it reads, because changing it belongs in its own change.
        });
    }

    private static boolean isNotSuccessful(ClassicHttpResponse response) {
        return response.getCode() < HttpStatus.SC_OK || response.getCode() > HttpStatus.SC_PARTIAL_CONTENT;
    }

    private Object doRequest(ClassicHttpRequest httpRequest, ResponseCheck check) {
        rateLimiter.acquire();
        return doRequest(targetHost, httpRequest, new RestClientResponseHandler(check))
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

        private final ResponseCheck check;

        RestClientResponseHandler(ResponseCheck check) {
            this.check = check;
        }

        @Override
        public String handleResponse(ClassicHttpResponse response) throws IOException {
            HttpEntity entity = response.getEntity();
            check.check(response, entity);
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
