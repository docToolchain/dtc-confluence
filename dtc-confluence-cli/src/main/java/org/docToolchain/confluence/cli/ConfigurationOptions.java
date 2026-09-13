package org.docToolchain.confluence.cli;

import java.io.File;
import java.io.FileNotFoundException;

import groovy.util.ConfigObject;
import org.docToolchain.configuration.ConfigBuilder;
import picocli.CommandLine.Option;

/**
 * The options every command needs: where the configuration lives, and the credentials.
 *
 * <p>Credentials are read from the environment rather than taken on the command line, so they do
 * not end up in a shell history or in the output of {@code ps}.</p>
 */
public class ConfigurationOptions {

    private static final String BEARER_TOKEN_VARIABLE = "CONFLUENCE_BEARER_TOKEN";
    private static final String CREDENTIALS_VARIABLE = "CONFLUENCE_CREDENTIALS";
    private static final String API_VARIABLE = "CONFLUENCE_API";

    @Option(names = {"-c", "--config"}, defaultValue = "docToolchainConfig.groovy",
            description = "Configuration file, relative to the document directory. "
                    + "Default: ${DEFAULT-VALUE}")
    private String configFile;

    @Option(names = {"-d", "--doc-dir"}, defaultValue = ".",
            description = "Directory the configuration and its paths are relative to. "
                    + "Default: ${DEFAULT-VALUE}")
    private String docDir;

    @Option(names = "--api", description = "Confluence API URL, overriding the configuration.")
    private String api;

    /**
     * @return the configuration, with credentials and any overrides folded in
     */
    public ConfigObject load() throws FileNotFoundException {
        ConfigObject config = new ConfigBuilder(docDir, configFile).build();
        applyCredentials(config);
        applyApiVersion(config);
        String apiUrl = api != null ? api : System.getenv(API_VARIABLE);
        if (apiUrl != null && !apiUrl.isEmpty()) {
            nested(config, "confluence").put("api", apiUrl);
        }
        return config;
    }

    public String docDir() {
        return new File(docDir).getPath();
    }

    /**
     * Chooses the API version when the configuration does not.
     *
     * <p>REST v2 exists in Cloud only; on Server and Data Center it answers 404. Cloud is
     * recognisable by its host, so the version can be derived rather than configured. docToolchain
     * defaults this in its Gradle wrapper, which is why the library never needed to - and why any
     * consumer without that wrapper used to reach a 404 with nothing pointing at the cause.</p>
     */
    private static void applyApiVersion(ConfigObject config) {
        ConfigObject confluence = nested(config, "confluence");
        if (confluence.get("useV1Api") instanceof Boolean) {
            return;
        }
        String apiUrl = String.valueOf(confluence.get("api"));
        boolean cloud = apiUrl.contains(".atlassian.net");
        confluence.put("useV1Api", !cloud);
        System.out.println("confluence.useV1Api is not set; assuming "
                + (cloud ? "Cloud, using API v2" : "Server or Data Center, using API v1")
                + " from the API URL.");
    }

    private static void applyCredentials(ConfigObject config) {
        ConfigObject confluence = nested(config, "confluence");
        String bearerToken = System.getenv(BEARER_TOKEN_VARIABLE);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            confluence.put("bearerToken", bearerToken);
            return;
        }
        String credentials = System.getenv(CREDENTIALS_VARIABLE);
        if (credentials != null && !credentials.isEmpty()) {
            confluence.put("credentials", credentials);
        }
    }

    private static ConfigObject nested(ConfigObject config, String key) {
        Object existing = config.get(key);
        if (existing instanceof ConfigObject nested) {
            return nested;
        }
        ConfigObject created = new ConfigObject();
        config.put(key, created);
        return created;
    }
}
