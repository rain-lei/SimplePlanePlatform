package io.loglens.api;

import java.util.List;

public record ChainBuildContext(String application, List<NodeDefinition> configuredNodes) {
    public ChainBuildContext { configuredNodes = List.copyOf(configuredNodes); }
}
