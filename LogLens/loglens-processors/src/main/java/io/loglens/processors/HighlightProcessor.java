package io.loglens.processors;

import io.loglens.api.*;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HighlightProcessor extends AbstractLogProcessor {
    private final boolean firstOccurrence;
    private final int maximum;
    private final List<String> keywords;
    private final Set<String> seen = new LinkedHashSet<>();
    public HighlightProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        super(definition, dependencies);
        Settings settings = new Settings(config(), nodeId());
        settings.only("firstOccurrence", "maxSeenTemplates", "keywords");
        firstOccurrence = settings.bool("firstOccurrence", true);
        maximum = settings.integer("maxSeenTemplates", 10000, 1, 100000);
        keywords = settings.strings("keywords", List.of());
    }
    @Override public void process(LogContext context, ChainContinuation next) {
        if (context.event().level() == LogLevel.ERROR || context.event().exception() != null) {
            context.tags().add("error"); hit(context, "error", "HIGHLIGHT", "error level or exception");
        }
        if (firstOccurrence && !seen.contains(context.template())) {
            if (seen.size() == maximum) { Iterator<String> iterator = seen.iterator(); iterator.next(); iterator.remove(); }
            seen.add(context.template());
            context.tags().add("first-occurrence"); hit(context, "first", "HIGHLIGHT", "first template in bounded cache");
        }
        for (String keyword : keywords) if (context.event().message().contains(keyword) || context.event().exceptionType().contains(keyword)) {
            context.tags().add("keyword"); hit(context, "keyword", "HIGHLIGHT", "configured keyword matched");
        }
        next.proceed(context);
    }
    public int seenCount() { return seen.size(); }
    @Override public void close() { seen.clear(); }
}
