package io.loglens.api;

import java.util.Map;

public abstract class AbstractLogProcessor implements LogProcessor {
    private final NodeDefinition definition;
    protected final NodeDependencies dependencies;
    protected AbstractLogProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        this.definition = definition;
        this.dependencies = dependencies;
    }
    @Override public final String nodeId() { return definition.id(); }
    protected final Map<String, Object> config() { return definition.config(); }
    protected final void hit(LogContext context, String rule, String action, String reason) {
        context.decisions().add(new RuleDecision(nodeId(), rule, action, reason));
    }
}
