package io.loglens.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Recursively freeze YAML-safe JDK values; no application classes are accepted. */
public final class ConfigValues {
    private ConfigValues() {}
    public static Map<String, Object> freeze(Map<String, ?> values) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        values.forEach((key, value) -> frozen.put(key, freezeValue(value)));
        return Collections.unmodifiableMap(frozen);
    }
    private static Object freezeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!(k instanceof String)) throw new IllegalArgumentException("config keys must be strings");
                copy.put((String) k, freezeValue(v));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(freezeValue(v)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("unsupported config value type: " + value.getClass().getName());
    }
}
