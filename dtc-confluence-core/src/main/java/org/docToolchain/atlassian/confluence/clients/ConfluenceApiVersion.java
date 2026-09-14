package org.docToolchain.atlassian.confluence.clients;

import java.net.URI;
import java.util.Locale;

import org.docToolchain.configuration.ConfigService;

/**
 * Decides which REST API a Confluence instance speaks.
 *
 * <p>v2 exists in Cloud only; Server and Data Center answer its paths with 404. Where the
 * configuration does not say, the deployment is recognised by the API host, so that a consumer
 * gets a working client rather than a 404 with nothing naming the cause.</p>
 */
public final class ConfluenceApiVersion {

    /** Confluence Cloud is reachable only under this host suffix, and REST v2 only on Cloud. */
    private static final String CLOUD_HOST_SUFFIX = ".atlassian.net";

    private ConfluenceApiVersion() {
    }

    /**
     * @return whether to speak v1, taking {@code confluence.useV1Api} when it is set and deriving
     *         it from {@code confluence.api} otherwise
     */
    public static boolean useV1(ConfigService configService) {
        Object configured = configService.getRawConfigProperty("confluence.useV1Api");
        if (configured instanceof Boolean set) {
            return set;
        }
        boolean cloud = isCloud(String.valueOf(configService.getConfigProperty("confluence.api")));
        System.out.println("confluence.useV1Api is not set; assuming "
                + (cloud ? "Cloud, using API v2" : "Server or Data Center, using API v1")
                + " from the API URL.");
        return !cloud;
    }

    /**
     * Recognises Cloud by host, not by substring: {@code https://example.atlassian.net.invalid/} is
     * not Cloud however much it reads like it, and a host is case insensitive.
     */
    private static boolean isCloud(String apiUrl) {
        try {
            String host = URI.create(apiUrl).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith(CLOUD_HOST_SUFFIX);
        } catch (IllegalArgumentException notAUri) {
            // Nothing to read a host from. Server and Data Center is the safer guess: a v1 endpoint
            // exists on Cloud as well, while v2 exists nowhere else.
            return false;
        }
    }
}
