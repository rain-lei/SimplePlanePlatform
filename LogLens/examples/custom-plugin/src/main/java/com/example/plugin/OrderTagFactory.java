package com.example.plugin;

import io.loglens.api.*;
import java.util.List;

/** Replace the highlight node by selecting provider example-order-tag in YAML. */
public final class OrderTagFactory implements LogProcessorFactory {
    @Override public String providerId() { return "example-order-tag"; }
    @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
        Settings settings = new Settings(definition.config(), definition.id());
        settings.only("tag");
        String tag = settings.string("tag", "order-focus");
        return new AbstractLogProcessor(definition, dependencies) {
            @Override public void process(LogContext context, ChainContinuation next) {
                if (context.fields().containsKey("orderId")) {
                    context.tags().add(tag);
                    hit(context, "order", "TAG", "extracted order identifier");
                }
                next.proceed(context);
            }
        };
    }
}
