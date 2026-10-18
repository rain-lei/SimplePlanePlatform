package io.loglens.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The single FINAL result consumed by all outputs. */
public record ProcessedRecord(String recordId, String sessionId, long recordSequence,
                              String status, String kind, LogLevel level, String logger,
                              String thread, String message, String template, ExceptionSnapshot exception,
                              Map<String, String> mdc, Map<String, String> fields, Set<String> tags,
                              List<RuleDecision> decisions, long firstSeen, long lastSeen,
                              long repeatCount, SourceRefs sourceRefs, String flushReason,
                              boolean processingFailed, boolean truncated) {
    public ProcessedRecord {
        mdc = Map.copyOf(mdc);
        fields = Map.copyOf(fields);
        tags = Set.copyOf(tags);
        decisions = List.copyOf(decisions);
    }
    public static ProcessedRecord from(String sessionId, long sequence, LogContext context) {
        CapturedLog event = context.event();
        return new ProcessedRecord(sessionId + ":r:" + sequence, sessionId, sequence,
                "FINAL", context.repeatCount() == 1 ? "NORMAL" : "AGGREGATE", event.level(),
                event.logger(), event.thread(), event.message(), context.template(), event.exception(),
                event.mdc(), context.fields(), context.tags(), context.decisions(), context.firstSeen(),
                context.lastSeen(), context.repeatCount(), context.sourceRefs(), context.flushReason(),
                context.processingFailed(), event.truncated());
    }
}
