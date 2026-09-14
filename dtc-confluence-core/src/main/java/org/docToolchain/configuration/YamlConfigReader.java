package org.docToolchain.configuration;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        String text = readFully(reader);
        // load() answers null for an empty document and for an explicit null alike. Composing
        // first tells them apart: no node at all is a file that says nothing, while "null" or "~"
        // is a document, and one that is not the mapping a configuration has to be.
        if (newYaml().compose(new StringReader(text)) == null) {
            return new ConfigObject();
        }
        Object loaded = newYaml().load(text);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "A configuration has to be a mapping at the top level, not a "
                            + (loaded == null ? "null"
                                    : loaded.getClass().getSimpleName().toLowerCase(Locale.ROOT)));
        }
        return (ConfigObject) toConfigObject(map,
                Collections.newSetFromMap(new IdentityHashMap<>()), new IdentityHashMap<>(), false);
    }

    private static String readFully(Reader reader) {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        try {
            for (int read = reader.read(buffer); read > -1; read = reader.read(buffer)) {
                text.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the configuration", e);
        }
        return text.toString();
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
     *
     * <p>Keys are read as text, because a configuration path is text. A file whose keys only differ
     * in type - {@code 1:} beside {@code "1":} - would otherwise lose one of them silently, so it
     * is refused instead.</p>
     */
    private static Map<String, Object> toConfigObject(Map<?, ?> map, Set<Object> enclosing,
                                                      Map<Object, Object> converted,
                                                      boolean insideList) {
        // Inside a list, a plain map: that is what ConfigSlurper leaves for an entry of
        // confluence.input, and the publisher asks such an entry for keys it may not have.
        // A ConfigObject answers those with an empty ConfigObject instead of null, which reads as
        // "configured" and overrides the global setting with nothing.
        Map<String, Object> config = insideList ? new LinkedHashMap<>() : new ConfigObject();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (config.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Two keys read as '" + key + "' in the same mapping. YAML tells them apart "
                                + "by type; a configuration path cannot.");
            }
            config.put(key, convert(entry.getValue(), enclosing, converted, insideList));
        }
        return config;
    }

    /**
     * Converts one value, refusing a container that contains itself.
     *
     * <p>A YAML anchor may refer to the node it is declared on, and such a document is legal for
     * the parser. Walking it here would not stop, so it is reported as the malformed configuration
     * it is rather than as a StackOverflowError. The alias limit does not help: one alias is
     * enough to tie the knot.</p>
     *
     * @param enclosing the containers currently being walked, by identity
     */
    private static Object convert(Object value, Set<Object> enclosing,
                                  Map<Object, Object> converted, boolean insideList) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            return value;
        }
        // A node still being walked means the document contains itself. Asked before the
        // memoisation below, because a node that is not finished cannot be handed out.
        if (enclosing.contains(value)) {
            throw new IllegalArgumentException(
                    "This configuration refers to itself; an anchor points at a node containing it.");
        }
        // An alias refers to a node the parser already built, so converting it again would rebuild
        // the whole subtree. A file can chain that: nine levels of nine references each is 81
        // aliases - under any sane alias limit - and three billion leaves. Measured before this:
        // OutOfMemoryError in a second. Converting each node once keeps the shape the parser gave.
        Object already = converted.get(value);
        if (already != null) {
            return already;
        }
        enclosing.add(value);
        try {
            if (value instanceof Map<?, ?> map) {
                Object result = toConfigObject(map, enclosing, converted, insideList);
                converted.put(value, result);
                return result;
            }
            List<?> list = (List<?>) value;
            // A mutable list: publishing appends discovered files to confluence.input when
            // inputHtmlFolder is set, and ConfigSlurper hands out a list that allows it.
            List<Object> elements = new ArrayList<>(list.size());
            for (Object element : list) {
                elements.add(convert(element, enclosing, converted, true));
            }
            converted.put(value, elements);
            return elements;
        } finally {
            enclosing.remove(value);
        }
    }
}
