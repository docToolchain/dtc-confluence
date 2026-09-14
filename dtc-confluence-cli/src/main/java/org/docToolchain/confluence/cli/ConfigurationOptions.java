package org.docToolchain.confluence.cli;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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
        // ConfigBuilder records the configuration file's own parent. With -d docs -c sub/x.groovy
        // that is docs/sub, while everything else here means docs, so paths in the configuration
        // would resolve against two different roots. --doc-dir wins.
        config.put("docDir", docDir);
        applyCredentials(config);
        // The override first: the API version is derived from the URL, so it has to be derived
        // from the URL that will actually be used.
        String apiUrl = api != null ? api : System.getenv(API_VARIABLE);
        if (apiUrl != null && !apiUrl.isEmpty()) {
            nested(config, "confluence").put("api", apiUrl);
        }
        return config;
    }

    public String docDir() {
        return new File(docDir).getPath();
    }

    private static void applyCredentials(ConfigObject config) {
        applyCredentials(config,
                System.getenv(BEARER_TOKEN_VARIABLE), System.getenv(CREDENTIALS_VARIABLE));
    }

    /**
     * Takes the two values rather than reading the environment itself, so that what they do to a
     * configuration can be stated in a test.
     */
    static void applyCredentials(ConfigObject config, String bearerToken, String credentials) {
        Map<Object, Object> confluence = nested(config, "confluence");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            confluence.put("bearerToken", bearerToken);
            return;
        }
        if (credentials != null && !credentials.isEmpty()) {
            // RestClient puts confluence.credentials straight behind "Basic ", so it has to arrive
            // encoded. docToolchain's Gradle wrapper encodes it before storing it, which is why the
            // library never had to; here the environment holds the plain "user:token".
            confluence.remove("bearerToken");
            confluence.put("credentials", base64(credentials));
        }
    }

    /**
     * @return {@code user:token} as the Basic authorization scheme wants it
     */
    private static String base64(String credentials) {
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return the section at {@code key}, creating it if it is not there yet
     */
    private static Map<Object, Object> nested(ConfigObject config, String key) {
        Object existing = config.get(key);
        if (existing instanceof Map<?, ?> section) {
            // Any map, not only a ConfigObject: ConfigSlurper leaves a section written as
            // "confluence = [api: ..., spaceKey: ...]" a plain LinkedHashMap, and replacing that
            // with a fresh ConfigObject would throw the whole configuration away.
            @SuppressWarnings("unchecked")
            Map<Object, Object> typed = (Map<Object, Object>) section;
            // ConfigObject.get creates a missing section without attaching it, so put it back.
            config.put(key, typed);
            return typed;
        }
        ConfigObject created = new ConfigObject();
        config.put(key, created);
        return created;
    }
}
