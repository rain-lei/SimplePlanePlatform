package io.loglens.processors;

import io.loglens.api.*;
import java.util.List;

public final class CorrelationProcessor extends AbstractLogProcessor {
    private final List<String> fields;
    public CorrelationProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        super(definition, dependencies);
        Settings settings = new Settings(config(), nodeId());
        settings.only("fields");
        fields = settings.strings("fields", List.of("traceId", "orderId"));
    }
    @Override public void process(LogContext context, ChainContinuation next) {
        for (String field : fields) {
            String value = context.fields().getOrDefault(field, context.event().mdc().get(field));
            if (value != null && !value.isBlank()) {
                context.fields().put(field, value);
                hit(context, field, "CORRELATE", "captured records only");
            }
        }
        next.proceed(context);
    }
}
