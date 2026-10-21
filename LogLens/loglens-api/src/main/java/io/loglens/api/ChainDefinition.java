package io.loglens.api;

import java.util.List;

public record ChainDefinition(List<NodeDefinition> nodes) {
    public ChainDefinition { nodes = List.copyOf(nodes); }
}
