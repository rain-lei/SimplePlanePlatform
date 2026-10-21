package io.loglens.api;

public interface LogProcessorFactory {
    String providerId();
    LogProcessor create(NodeDefinition definition, NodeDependencies dependencies);
}
