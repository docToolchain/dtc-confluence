package org.docToolchain.configuration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import groovy.util.ConfigObject;
import groovy.util.ConfigSlurper;

/**
 * Parses the Groovy configuration file into a {@link ConfigObject}.
 *
 * <p>The configuration format stays Groovy for compatibility with docToolchain. New consumers are
 * meant to use the typed configuration instead; see {@code docs/decisions.adoc}.</p>
 */
public class ConfigBuilder {

    private final File configFile;
    private final ConfigSlurper configSlurper = new ConfigSlurper();

    public ConfigBuilder(String docDir, String mainConfigFile) {
        this.configFile = new File(docDir, mainConfigFile);
    }

    /**
     * Runs {@code action} only when no configuration file is there yet, so a caller can write a
     * default one without first having to ask whether it is needed.
     */
    public ConfigBuilder prepareConfigFileIfNotExists(Runnable action) {
        if (!configFile.exists()) {
            action.run();
        }
        return this;
    }

    public ConfigObject build() throws FileNotFoundException {
        if (!configFile.exists()) {
            throw new FileNotFoundException("Config file does not exist: " + canonicalPath());
        }
        ConfigObject config = isYaml(configFile.getName())
                ? new YamlConfigReader().read(configFile.toPath())
                : configSlurper.parse(readConfigFile());
        config.put("docDir", configFile.getParent());
        config.put("mainConfigFile", configFile.getName());
        return config;
    }

    /**
     * @return whether this name asks to be read as YAML rather than as Groovy
     */
    public static boolean isYaml(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private String readConfigFile() {
        try {
            return Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config file " + canonicalPath(), e);
        }
    }

    private String canonicalPath() {
        try {
            return configFile.getCanonicalPath();
        } catch (IOException e) {
            return configFile.getAbsolutePath();
        }
    }
}
