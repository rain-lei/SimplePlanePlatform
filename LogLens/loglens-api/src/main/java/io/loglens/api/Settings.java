package io.loglens.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict typed access used by configuration and processor factories. */
public final class Settings {
    private final Map<String, Object> values;
    private final String path;
    public Settings(Map<String, Object> values, String path) { this.values = values; this.path = path; }
    public void only(String... keys) {
        Set<String> allowed = Set.of(keys);
        for (String key : values.keySet()) if (!allowed.contains(key)) throw error(key, "unknown field");
    }
    public String string(String key, String fallback) {
        Object v = values.get(key);
        if (v == null && !values.containsKey(key)) return fallback;
        if (!(v instanceof String s) || s.isBlank()) throw error(key, "expected nonempty string");
        return s;
    }
    public boolean bool(String key, boolean fallback) {
        if (!values.containsKey(key)) return fallback;
        Object v = values.get(key);
        if (!(v instanceof Boolean b)) throw error(key, "expected boolean");
        return b;
    }
    public long number(String key, long fallback, long min, long max) {
        if (!values.containsKey(key)) return fallback;
        Object v = values.get(key);
        if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long)) throw error(key, "expected integer");
        long result = ((Number) v).longValue();
        if (result < min || result > max) throw error(key, "must be in [" + min + ", " + max + "]");
        return result;
    }
    public int integer(String key, int fallback, int min, int max) { return (int) number(key, fallback, min, max); }
    public List<String> strings(String key, List<String> fallback) {
        if (!values.containsKey(key)) return List.copyOf(fallback);
        Object v = values.get(key);
        if (!(v instanceof List<?> list)) throw error(key, "expected string list");
        if (list.size() > 1024) throw error(key, "too many entries");
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String s) || s.isBlank()) throw error(key, "expected nonempty strings");
            result.add(s);
        }
        return List.copyOf(result);
    }
    public Map<String, Object> map(String key) {
        if (!values.containsKey(key)) return Map.of();
        return asMap(values.get(key), path + "." + key);
    }
    public List<Map<String, Object>> maps(String key) {
        if (!values.containsKey(key)) return List.of();
        Object v = values.get(key);
        if (!(v instanceof List<?> list)) throw error(key, "expected mapping list");
        if (list.size() > 1024) throw error(key, "too many entries");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) result.add(asMap(entry, path + "." + key));
        return List.copyOf(result);
    }
    public <T extends Enum<T>> T enumeration(String key, T fallback, Class<T> type) {
        String name = string(key, fallback.name());
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException e) { throw error(key, "unsupported value " + name); }
    }
    public IllegalArgumentException error(String key, String reason) { return new IllegalArgumentException(path + "." + key + ": " + reason); }
    public static Map<String, Object> asMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(path + ": expected mapping");
        for (Object key : map.keySet()) if (!(key instanceof String)) throw new IllegalArgumentException(path + ": keys must be strings");
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }
}
