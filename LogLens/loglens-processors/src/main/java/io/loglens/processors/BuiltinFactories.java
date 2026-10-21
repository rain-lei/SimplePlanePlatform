package io.loglens.processors;

import io.loglens.api.*;
import java.util.List;
import java.util.function.BiFunction;

public final class BuiltinFactories {
    private BuiltinFactories() {}
    private record Factory(String providerId, BiFunction<NodeDefinition, NodeDependencies, LogProcessor> constructor) implements LogProcessorFactory {
        @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) { return constructor.apply(definition, dependencies); }
    }
    public static List<LogProcessorFactory> all() {
        return List.of(new Factory("builtin-field-extract", FieldExtractProcessor::new),
                new Factory("builtin-template", TemplateProcessor::new), new Factory("builtin-repeat-fold", RepeatFoldProcessor::new),
                new Factory("builtin-correlation", CorrelationProcessor::new), new Factory("builtin-highlight", HighlightProcessor::new));
    }
}
