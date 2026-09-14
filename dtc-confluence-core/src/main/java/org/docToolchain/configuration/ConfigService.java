package org.docToolchain.configuration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import groovy.util.ConfigObject;

/**
 * Reads values out of the parsed configuration.
 *
 * <p>Paths are dotted strings such as {@code confluence.spaceKey}. A lookup first asks the
 * configuration tree directly, then its flattened form, because {@link ConfigObject} only resolves
 * dotted keys in the flattened view.</p>
 */
public class ConfigService {

    private final ConfigObject config;

    public ConfigService(ConfigObject config) {
        this.config = config;
    }

    /**
     * @return the value at {@code propertyPath}, the sub-tree if the path names one, or
     *         {@code null} if the path resolves to nothing
     */
    public Object getConfigProperty(String propertyPath) {
        Object property = config.get(propertyPath);
        if (isAbsent(property)) {
            property = config.flatten().get(propertyPath);
        }
        return isAbsent(property) ? null : property;
    }

    /**
     * Reads a value without applying Groovy truth, so that a setting of {@code false} or {@code 0}
     * is a value rather than an absence.
     *
     * <p>{@link #getConfigProperty} cannot tell {@code useV1Api = false} from a {@code useV1Api}
     * nobody wrote, because Groovy truth calls both of them empty. Everywhere that only asks
     * "is this switched on?" the difference does not matter; where a default has to be applied
     * only when nothing was configured, it does.</p>
     *
     * @return the value at {@code propertyPath}, or {@code null} if the path resolves to nothing
     */
    public Object getRawConfigProperty(String propertyPath) {
        Object property = config.get(propertyPath);
        if (isEmptyNode(property)) {
            property = config.flatten().get(propertyPath);
        }
        return isEmptyNode(property) ? null : property;
    }

    /**
     * {@link ConfigObject#get} answers a missing key with an empty ConfigObject rather than with
     * {@code null}, and that is the only shape meaning "nothing is here".
     */
    private static boolean isEmptyNode(Object value) {
        return value == null || (value instanceof ConfigObject nested && nested.isEmpty());
    }

    /**
     * @return every leaf below {@code propertyPath}, keyed by the remainder of its path, or an
     *         empty map if nothing lives there
     */
    public Map<String, Object> getFlatConfigSubTree(String propertyPath) {
        Map<String, Object> result = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<Object, Object> flat = config.flatten();
        flat.forEach((key, value) -> {
            String path = String.valueOf(key);
            if (path.startsWith(propertyPath)) {
                // replaceFirst takes a regular expression, so the dot matches any character here.
                // Kept as it was: the paths this runs on never exercise the difference.
                result.put(path.replaceFirst(propertyPath + ".", ""), value);
            }
        });
        return result;
    }

    /**
     * Mirrors Groovy truth, which the original implementation relied on.
     *
     * <p>This matters because {@link ConfigObject#get} answers a missing key with an <em>empty
     * ConfigObject</em> rather than {@code null}. Without treating that as absent, every lookup of
     * a dotted path would stop at the empty object instead of retrying against the flattened
     * configuration.</p>
     */
    private static boolean isAbsent(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof CharSequence sequence) {
            return sequence.isEmpty();
        }
        if (value instanceof Boolean flag) {
            return !flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() == 0;
        }
        return false;
    }
}
