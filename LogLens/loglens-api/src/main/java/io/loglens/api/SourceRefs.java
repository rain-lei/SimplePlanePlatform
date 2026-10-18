package io.loglens.api;

import java.util.List;

/** Persistent query reference with bounded examples, never an unbounded event ID list. */
public record SourceRefs(List<String> groupBy, List<String> groupValues,
                         long firstCaptureSequence, long lastCaptureSequence,
                         List<String> sampleEventIds) {
    public SourceRefs {
        groupBy = List.copyOf(groupBy);
        groupValues = List.copyOf(groupValues);
        sampleEventIds = List.copyOf(sampleEventIds);
        if (groupBy.size() != groupValues.size()) throw new IllegalArgumentException("group key arity");
    }
    public static SourceRefs single(CapturedLog event) {
        return new SourceRefs(List.of(), List.of(), event.captureSequence(), event.captureSequence(), List.of(event.eventId()));
    }
}
