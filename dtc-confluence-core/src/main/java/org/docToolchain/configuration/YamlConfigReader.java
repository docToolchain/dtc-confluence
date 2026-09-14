package org.docToolchain.configuration;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import groovy.util.ConfigObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a YAML configuration into the {@link ConfigObject} the rest of the code expects.
 *
 * <p>YAML is the format this project means to be configured in; the Groovy form stays readable for
 * compatibility with docToolchain. Both end up as the same object, so nothing downstream has to
 * know which one was on disk.</p>
 *
 * <p>Loaded with a {@link SafeConstructor}: a configuration file is data, and nothing in it should
 * be able to name a class to instantiate.</p>
 */
public class YamlConfigReader {

    /** A configuration is small; anything larger is a mistake worth reporting as one. */
    private static final int MAX_ALIASES = 100;

    public ConfigObject read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config file " + file, e);
        }
    }

    ConfigObject read(Reader reader) {
        Object loaded = newYaml().load(reader);
        if (loaded == null) {
            // An empty file is a configuration that says nothing, not an error.
            return new ConfigObject();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "A configuration has to be a mapping at the top level, not a "
                            + loaded.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        }
        return toConfigObject(map);
    }

    private static Yaml newYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options), new org.yaml.snakeyaml.representer.Representer(
                new org.yaml.snakeyaml.DumperOptions()), new org.yaml.snakeyaml.DumperOptions(), options);
    }

    /**
     * Nested mappings become nested ConfigObjects rather than plain maps, so that a dotted lookup
     * finds them: {@link ConfigObject#flatten} only walks its own kind.
     */
    private static ConfigObject toConfigObject(Map<?, ?> map) {
        ConfigObject config = new ConfigObject();
        map.forEach((key, value) -> config.put(String.valueOf(key), convert(value)));
        return config;
    }

    private static Object convert(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toConfigObject(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(YamlConfigReader::convert).toList();
        }
        return value;
    }
}
