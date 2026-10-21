package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.LogLensConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Redaction belongs outside the replaceable semantic chain and also protects final plugin output. */
public final class Redactor {
    private final LogLensConfig config;
    public Redactor(LogLensConfig config) { this.config = config; }
    public String text(String input, int maximum) {
        String value = input == null ? "" : input;
        for (LogLensConfig.MessageRule rule : config.redaction().messageRules()) value = rule.pattern().matcher(value).replaceAll(rule.replacement());
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
    public CapturedLog captured(CapturedLog input) {
        var c = config.capture();
        Map<String, String> mdc = new LinkedHashMap<>(); boolean truncated = input.truncated();
        for (Map.Entry<String, String> entry : input.mdc().entrySet()) {
            if (mdc.size() == c.maxMdcEntries()) { truncated = true; break; }
            String key = text(entry.getKey(), 256), original = entry.getValue();
            String value = config.redaction().mdcKeys().contains(entry.getKey()) ? "***" : text(original, c.maxMdcValueChars());
            mdc.put(key, value); truncated |= original.length() > c.maxMdcValueChars();
        }
        List<String> parameters = new ArrayList<>();
        for (String parameter : input.parameters()) {
            if (parameters.size() == c.maxParameters()) { truncated = true; break; }
            parameters.add(text(parameter, c.maxParameterChars())); truncated |= parameter.length() > c.maxParameterChars();
        }
        String message = text(input.message(), c.maxMessageChars()), template = text(input.template(), c.maxMessageChars());
        truncated |= input.message().length() > c.maxMessageChars() || input.template().length() > c.maxMessageChars();
        ExceptionSnapshot exception = exception(input.exception(), 0);
        return new CapturedLog(input.eventId(), input.sessionId(), input.captureSequence(), input.eventTime(), input.capturedAt(), input.monotonicNanos(),
                input.level(), text(input.logger(), 1024), text(input.thread(), 1024), template, message, parameters, mdc, exception,
                input.caller().stream().limit(c.maxStackFrames()).map(s -> text(s, 2048)).toList(), truncated || exception != null && exception.truncated());
    }
    private ExceptionSnapshot exception(ExceptionSnapshot input, int depth) {
        if (input == null) return null;
        if (depth >= 16) return new ExceptionSnapshot(text(input.type(), 512), "[exception depth truncated]", List.of(), List.of(), null, true);
        var c = config.capture();
        boolean truncated = input.truncated() || input.frames().size() > c.maxStackFrames() || input.message().length() > c.maxExceptionChars() || input.suppressed().size() > 8;
        List<ExceptionSnapshot> suppressed = input.suppressed().stream().limit(8).map(s -> exception(s, depth + 1)).toList();
        ExceptionSnapshot cause = exception(input.cause(), depth + 1);
        return new ExceptionSnapshot(text(input.type(), 512), text(input.message(), c.maxExceptionChars()),
                input.frames().stream().limit(c.maxStackFrames()).map(s -> text(s, 2048)).toList(), suppressed, cause,
                truncated || suppressed.stream().anyMatch(ExceptionSnapshot::truncated) || cause != null && cause.truncated());
    }
    public LogContext output(LogContext input) {
        LogContext result = new LogContext(captured(input.event()));
        result.template(text(input.template(), config.capture().maxMessageChars()));
        for (var entry : input.fields().entrySet()) {
            if (result.fields().size() == 128) break;
            result.fields().put(text(entry.getKey(), 256), sensitiveField(entry.getKey()) ? "***"
                    : text(entry.getValue(), config.capture().maxMdcValueChars()));
        }
        input.tags().stream().limit(128).map(s -> text(s, 256)).forEach(result.tags()::add);
        input.decisions().stream().limit(256).map(d -> new RuleDecision(text(d.nodeId(), 80), text(d.ruleId(), 256), text(d.action(), 80), text(d.reason(), 1024))).forEach(result.decisions()::add);
        SourceRefs refs = input.sourceRefs();
        if (refs.groupBy().size() > 32 || refs.sampleEventIds().size() > 32) throw new IllegalArgumentException("plugin source reference exceeds limits");
        SourceRefs safeRefs = sourceRefs(refs);
        result.aggregation(input.repeatCount(), input.firstSeen(), input.lastSeen(), safeRefs, text(input.flushReason(), 80));
        result.processingFailed(input.processingFailed());
        return result;
    }
    public SourceRefs sourceRefs(SourceRefs refs) {
        if (refs.groupBy().size() > 32 || refs.groupValues().size() > 32 || refs.sampleEventIds().size() > 32)
            throw new IllegalArgumentException("plugin source reference exceeds limits");
        List<String> groupBy = refs.groupBy().stream().map(s -> text(s, 256)).toList();
        List<String> groupValues = sourceGroupValues(refs.groupBy(), refs.groupValues());
        return new SourceRefs(groupBy, groupValues,
                refs.firstCaptureSequence(), refs.lastCaptureSequence(), refs.sampleEventIds().stream().map(s -> text(s, 256)).toList());
    }
    public List<String> sourceGroupBy(List<String> groupBy) {
        if (groupBy.size() > 32) throw new IllegalArgumentException("plugin source reference exceeds limits");
        return groupBy.stream().map(s -> text(s, 256)).toList();
    }
    public List<String> sourceGroupValues(List<String> groupBy, List<String> values) {
        if (groupBy.size() != values.size() || values.size() > 32) throw new IllegalArgumentException("plugin source reference arity");
        return java.util.stream.IntStream.range(0, values.size()).mapToObj(i -> {
            return sensitiveField(groupBy.get(i)) ? "***"
                    : text(values.get(i), config.capture().maxMessageChars());
        }).toList();
    }
    private boolean sensitiveField(String field) {
        String mdcKey = field.startsWith("mdc.") ? field.substring(4)
                : field.startsWith("fields.") ? field.substring(7) : field;
        return config.redaction().mdcKeys().contains(mdcKey);
    }
}
