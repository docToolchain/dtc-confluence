package org.docToolchain.http;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.net.URIBuilder;

/**
 * Shared HTTP plumbing for the Confluence clients: builds the client, stamps a user agent
 * and a Host header onto every request, and executes requests against a target host.
 */
public abstract class BasicRestClient {

    private static final String USER_AGENT_PREFIX = "docToolchain_v";

    protected HttpClientBuilder httpClientBuilder;

    protected BasicRestClient() {
        this.httpClientBuilder = HttpClientBuilder.create();
        httpClientBuilder.addRequestInterceptorFirst((request, entityDetails, context) -> {
            request.setHeader(HttpHeaders.USER_AGENT, userAgent());
            setHostHeaderIfAbsent(request);
        });
    }

    private String userAgent() {
        return USER_AGENT_PREFIX + getClass().getPackage().getImplementationVersion();
    }

    /**
     * Sets the Host header from the request URI unless it was set explicitly already.
     */
    private static void setHostHeaderIfAbsent(HttpRequest request) throws ProtocolException {
        Header hostHeader = request.getHeader(HttpHeaders.HOST);
        if (hostHeader != null) {
            return;
        }
        try {
            request.setHeader(HttpHeaders.HOST, new URIBuilder(request.getUri().toString()).getHost());
        } catch (URISyntaxException e) {
            throw new ProtocolException("cannot derive the Host header from the request URI", e);
        }
    }

    /**
     * @param <T> what the handler makes of the response - text for an API call, bytes for a
     *            download
     */
    public <T> Optional<T> doRequest(HttpHost targetHost, ClassicHttpRequest httpRequest,
                                     HttpClientResponseHandler<T> responseHandler) {
        try (CloseableHttpClient httpClient = httpClientBuilder.build()) {
            return Optional.ofNullable(httpClient.execute(targetHost, httpRequest, responseHandler));
        } catch (IOException e) {
            System.out.println("Error while executing request: \n"
                    + "request:" + describe(httpRequest) + ",\n"
                    + "targetHost:" + targetHost.toURI() + "\n"
                    + "reason:" + e.getMessage() + "\n");
            throw new RuntimeException(e);
        }
    }

    private static String describe(ClassicHttpRequest request) {
        try {
            return request.getMethod() + " " + request.getUri();
        } catch (URISyntaxException e) {
            return request.getMethod() + " <unparseable URI>";
        }
    }

    protected HttpClientBuilder getHttpClientBuilder() {
        return httpClientBuilder;
    }
}
