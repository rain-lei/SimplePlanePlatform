package io.loglens.processors;

import io.loglens.api.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RepeatFoldProcessor extends AbstractLogProcessor {
    private static final class Group {
        final LogContext summary;
        final long startNanos;
        final List<String> values;
        final List<String> samples = new ArrayList<>();
        long count = 1;
        long lastSeen;
        long lastSequence;
        Group(LogContext context, List<String> values) {
            summary = context.copy(); startNanos = context.event().monotonicNanos(); this.values = values;
            lastSeen = context.event().capturedAt(); lastSequence = context.event().captureSequence();
        }
    }
    private final long windowNanos;
    private final int maxGroups;
    private final int maxSamples;
    private final Set<LogLevel> bypass;
    private final List<String> groupBy;
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    public RepeatFoldProcessor(NodeDefinition definition, NodeDependencies dependencies) {
        super(definition, dependencies);
        Settings settings = new Settings(config(), nodeId());
        settings.only("windowMillis", "maxGroups", "maxSamplesPerGroup", "bypassLevels", "groupBy");
        windowNanos = TimeUnit.MILLISECONDS.toNanos(settings.number("windowMillis", 3000, 1, 3600000));
        maxGroups = settings.integer("maxGroups", 10000, 1, 100000);
        maxSamples = settings.integer("maxSamplesPerGroup", 3, 0, 32);
        Set<LogLevel> levels = new HashSet<>();
        for (String level : settings.strings("bypassLevels", List.of("ERROR"))) {
            try { levels.add(LogLevel.valueOf(level)); } catch (IllegalArgumentException e) { throw settings.error("bypassLevels", "unsupported level"); }
        }
        bypass = Set.copyOf(levels);
        groupBy = settings.strings("groupBy", List.of("logger", "level", "template", "exceptionType"));
        if (!groupBy.containsAll(List.of("logger", "level", "template", "exceptionType")) || new HashSet<>(groupBy).size() != groupBy.size())
            throw settings.error("groupBy", "unique keys must include logger, level, template and exceptionType");
    }
    @Override public void beforeProcess(FlushContext flush, LogContext context, EventEmitter downstream) {
        flushExpired(flush.monotonicNanos(), downstream, "WINDOW");
        if (!bypass.contains(context.event().level())) {
            List<String> values = groupBy.stream().map(field -> GroupKey.value(context, field)).toList();
            if (groups.size() == maxGroups && !groups.containsKey(GroupKey.encode(groupBy, values))) {
                Iterator<Map.Entry<String, Group>> iterator = groups.entrySet().iterator();
                Group oldest = iterator.next().getValue(); iterator.remove();
                downstream.emit(finalizeGroup(oldest, "CAPACITY"));
            }
        }
    }
    @Override public void process(LogContext context, ChainContinuation next) {
        if (bypass.contains(context.event().level())) {
            hit(context, "bypass", "BYPASS", "configured immediate level"); next.proceed(context); return;
        }
        List<String> values = groupBy.stream().map(field -> GroupKey.value(context, field)).toList();
        dependencies.sourceIndex().associate(context.event().captureSequence(), groupBy, values);
        String key = GroupKey.encode(groupBy, values);
        Group group = groups.get(key);
        if (group == null) {
            if (groups.size() == maxGroups) throw new IllegalStateException("beforeProcess must run before new fold group");
            group = new Group(context, values); groups.put(key, group);
        } else {
            group.count++;
            group.lastSeen = context.event().capturedAt();
            group.lastSequence = context.event().captureSequence();
        }
        if (group.samples.size() < maxSamples) group.samples.add(context.event().eventId());
        hit(context, "window", "BUFFER", "awaiting fixed window finalization");
    }
    private LogContext finalizeGroup(Group group, String reason) {
        LogContext result = group.summary.copy();
        result.aggregation(group.count, group.summary.firstSeen(), group.lastSeen,
                new SourceRefs(groupBy, group.values, group.summary.event().captureSequence(), group.lastSequence, group.samples), reason);
        hit(result, "window", group.count > 1 ? "AGGREGATE" : "NORMAL", reason);
        return result;
    }
    private void flushExpired(long now, EventEmitter downstream, String reason) {
        Iterator<Map.Entry<String, Group>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Group group = iterator.next().getValue();
            if (now - group.startNanos >= windowNanos) { iterator.remove(); downstream.emit(finalizeGroup(group, reason)); }
        }
    }
    @Override public void flush(FlushContext context, EventEmitter downstream) {
        if (context.all()) {
            Iterator<Group> iterator = groups.values().iterator();
            while (iterator.hasNext()) { Group group = iterator.next(); iterator.remove(); downstream.emit(finalizeGroup(group, context.reason())); }
        } else flushExpired(context.monotonicNanos(), downstream, "WINDOW");
    }
    public int groupCount() { return groups.size(); }
    @Override public void close() { groups.clear(); }
}
