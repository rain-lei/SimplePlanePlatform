package io.loglens.api;

import java.util.Map;

public record NodeDefinition(String id, String provider, Map<String, Object> config) {
    public NodeDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("node id required");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("node provider required");
        config = ConfigValues.freeze(config);
    }
}
