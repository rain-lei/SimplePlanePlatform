package io.loglens.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Consumer-confined semantic state. Plugins must not retain this object or continuations. */
public final class LogContext {
    private final CapturedLog event;
    private String template;
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final Set<String> tags = new LinkedHashSet<>();
    private final List<RuleDecision> decisions = new ArrayList<>();
    private long repeatCount = 1;
    private long firstSeen;
    private long lastSeen;
    private SourceRefs sourceRefs;
    private String flushReason = "IMMEDIATE";
    private boolean processingFailed;
    public LogContext(CapturedLog event) {
        this.event = Objects.requireNonNull(event);
        this.template = event.template();
        firstSeen = lastSeen = event.capturedAt();
        sourceRefs = SourceRefs.single(event);
    }
    public CapturedLog event() { return event; }
    public String template() { return template; }
    public void template(String value) { template = Objects.requireNonNull(value); }
    public Map<String, String> fields() { return fields; }
    public Set<String> tags() { return tags; }
    public List<RuleDecision> decisions() { return decisions; }
    public long repeatCount() { return repeatCount; }
    public long firstSeen() { return firstSeen; }
    public long lastSeen() { return lastSeen; }
    public SourceRefs sourceRefs() { return sourceRefs; }
    public String flushReason() { return flushReason; }
    public boolean processingFailed() { return processingFailed; }
    public void processingFailed(boolean value) { processingFailed = value; }
    public void aggregation(long count, long first, long last, SourceRefs refs, String reason) {
        if (count < 1) throw new IllegalArgumentException("repeatCount must be positive");
        repeatCount = count;
        firstSeen = first;
        lastSeen = last;
        sourceRefs = Objects.requireNonNull(refs);
        flushReason = Objects.requireNonNull(reason);
    }
    public LogContext copy() {
        LogContext copy = new LogContext(event);
        copy.template = template;
        copy.fields.putAll(fields);
        copy.tags.addAll(tags);
        copy.decisions.addAll(decisions);
        copy.aggregation(repeatCount, firstSeen, lastSeen, sourceRefs, flushReason);
        copy.processingFailed = processingFailed;
        return copy;
    }
}
