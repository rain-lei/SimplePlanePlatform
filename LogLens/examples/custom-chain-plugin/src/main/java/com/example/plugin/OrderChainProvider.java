package com.example.plugin;

import io.loglens.api.*;
import java.util.List;
import java.util.Map;

/** Whole-chain example uses builtins, while runtime still owns redaction and final outputs. */
public final class OrderChainProvider implements LogChainProvider {
    @Override public ChainDefinition create(ChainBuildContext context) {
        return new ChainDefinition(List.of(
                new NodeDefinition("orders", "builtin-field-extract", Map.of("rules", List.of(
                        Map.of("field", "orderId", "pattern", "orderId=(?<value>[A-Za-z0-9-]+)")))),
                new NodeDefinition("templates", "builtin-template", Map.of()),
                new NodeDefinition("links", "builtin-correlation", Map.of("fields", List.of("orderId", "traceId"))),
                new NodeDefinition("emphasis", "builtin-highlight", Map.of("firstOccurrence", true))));
    }
}
