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
        ConfigObject confluence = nested(config, "confluence");
        String bearerToken = System.getenv(BEARER_TOKEN_VARIABLE);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            confluence.put("bearerToken", bearerToken);
            return;
        }
        String credentials = System.getenv(CREDENTIALS_VARIABLE);
        if (credentials != null && !credentials.isEmpty()) {
            // RestClient prefers a bearer token, so one left in the configuration file would
            // quietly beat the credentials given here.
            confluence.remove("bearerToken");
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
