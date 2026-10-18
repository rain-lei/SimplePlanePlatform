package io.loglens.processors;

import io.loglens.api.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProcessorTest {
    static final class Clock implements LogClock {
        long wall = 1000, nanos;
        public long wallTimeMillis() { return wall; }
        public long monotonicNanos() { return nanos; }
        void advance(long millis) { wall += millis; nanos += TimeUnit.MILLISECONDS.toNanos(millis); }
    }
    final Clock clock = new Clock();
    final List<Long> indexed = new ArrayList<>();
    final NodeDependencies dependencies = new NodeDependencies(clock, (code, message) -> {}, (sequence, by, values) -> indexed.add(sequence));
    final List<LogContext> output = new ArrayList<>();
    private LogContext event(long sequence, LogLevel level, String template, String exception, String trace) {
        ExceptionSnapshot ex = exception == null ? null : new ExceptionSnapshot(exception, "failure", List.of(), List.of(), null, false);
        return new LogContext(new CapturedLog("e" + sequence, "s", sequence, clock.wall, clock.wall, clock.nanos,
                level, "orders", "main", template, "orderId=O-" + sequence, List.of("O-" + sequence),
                Map.of("traceId", trace), ex, List.of(), false));
    }
    private RepeatFoldProcessor fold(Map<String, Object> options) { return new RepeatFoldProcessor(new NodeDefinition("fold", "builtin-repeat-fold", options), dependencies); }
    private void feed(LogProcessor processor, LogContext event) {
        processor.beforeProcess(new FlushContext(event.event().monotonicNanos(), event.event().capturedAt(), "INPUT", false), event, context -> output.add(context.copy()));
        processor.process(event, context -> output.add(context.copy()));
    }
    @Test void fixedWindowUsesMonotonicTimeAndDoesNotExtendOnRepeats() {
        RepeatFoldProcessor fold = fold(Map.of("windowMillis", 100));
        feed(fold, event(1, LogLevel.INFO, "order {}", null, "t1"));
        clock.advance(90);
        feed(fold, event(2, LogLevel.INFO, "order {}", null, "t2"));
        clock.wall -= 100000; // Wall clock jumps do not affect duration.
        clock.nanos += TimeUnit.MILLISECONDS.toNanos(10);
        fold.flush(new FlushContext(clock.nanos, clock.wall, "TICK", false), c -> output.add(c.copy()));
        assertEquals(1, output.size());
        assertEquals(2, output.get(0).repeatCount());
        assertEquals(List.of("e1", "e2"), output.get(0).sourceRefs().sampleEventIds());
        assertEquals(List.of(1L, 2L), indexed);
        assertEquals(0, fold.groupCount());
    }
    @Test void errorIsImmediateAndExceptionTypesNeverCollapse() {
        RepeatFoldProcessor fold = fold(Map.of());
        feed(fold, event(1, LogLevel.ERROR, "order {}", "java.lang.IllegalStateException", "a"));
        assertEquals(1, output.size());
        feed(fold, event(2, LogLevel.WARN, "order {}", "java.lang.IllegalStateException", "a"));
        feed(fold, event(3, LogLevel.WARN, "order {}", "java.lang.NullPointerException", "a"));
        assertEquals(2, fold.groupCount());
        fold.flush(new FlushContext(clock.nanos, clock.wall, "STOP", true), c -> output.add(c.copy()));
        assertEquals(3, output.size());
        assertTrue(output.stream().allMatch(c -> c.repeatCount() == 1));
    }
    @Test void capacityFlushesOldestAndSamplesStayBounded() {
        RepeatFoldProcessor fold = fold(Map.of("maxGroups", 1, "maxSamplesPerGroup", 1));
        for (int i = 1; i <= 100; i++) feed(fold, event(i, LogLevel.INFO, "same {}", null, "a"));
        feed(fold, event(101, LogLevel.INFO, "other {}", null, "a"));
        assertEquals(1, output.size());
        assertEquals("CAPACITY", output.get(0).flushReason());
        assertEquals(100, output.get(0).repeatCount());
        assertEquals(1, output.get(0).sourceRefs().sampleEventIds().size());
        assertEquals(1, fold.groupCount());
    }
    @Test void groupingCanIncludeTraceAndGroupEncodingCannotCollide() {
        RepeatFoldProcessor fold = fold(Map.of("groupBy", List.of("logger", "level", "template", "exceptionType", "traceId")));
        feed(fold, event(1, LogLevel.INFO, "same {}", null, "a"));
        feed(fold, event(2, LogLevel.INFO, "same {}", null, "b"));
        assertEquals(2, fold.groupCount());
        assertNotEquals(GroupKey.encode(List.of("a", "b"), List.of("x:y", "z")), GroupKey.encode(List.of("a", "b"), List.of("x", "y:z")));
    }
    @Test void subsequentInputFlushesExpiredGroupBeforeProcessingNewOne() {
        RepeatFoldProcessor fold = fold(Map.of("windowMillis", 10));
        feed(fold, event(1, LogLevel.INFO, "same {}", null, "a"));
        clock.advance(11);
        feed(fold, event(2, LogLevel.INFO, "same {}", null, "a"));
        assertEquals(1, output.size());
        assertEquals(1, output.get(0).repeatCount());
        assertEquals(1, fold.groupCount());
    }
    @Test void defaultNodesExtractCorrelateAndHighlight() {
        LogContext context = event(1, LogLevel.ERROR, "order {}", "java.lang.NullPointerException", "trace-1");
        new FieldExtractProcessor(new NodeDefinition("extract", "builtin-field-extract", Map.of(
                "rules", List.of(Map.of("field", "orderId", "pattern", "orderId=(?<value>[A-Za-z0-9-]+)")),
                "mdcMapping", Map.of("trace", "traceId"))), dependencies).process(context, c -> {});
        new TemplateProcessor(new NodeDefinition("template", "builtin-template", Map.of()), dependencies).process(context, c -> {});
        new CorrelationProcessor(new NodeDefinition("correlate", "builtin-correlation", Map.of()), dependencies).process(context, c -> {});
        new HighlightProcessor(new NodeDefinition("highlight", "builtin-highlight", Map.of("keywords", List.of("NullPointerException"))), dependencies).process(context, c -> {});
        assertEquals("O-1", context.fields().get("orderId"));
        assertEquals("trace-1", context.fields().get("traceId"));
        assertEquals("order {}", context.template());
        assertTrue(context.tags().containsAll(List.of("error", "first-occurrence", "keyword")));
        assertFalse(context.decisions().isEmpty());
    }
    @Test void plainMessagesNormalizeByConfiguredRulesAndFirstCacheIsBounded() {
        LogContext context = new LogContext(new CapturedLog("e", "s", 1, 0, 0, 0, LogLevel.INFO, "l", "t", "", "orderId=123", List.of(), Map.of(), null, List.of(), false));
        new TemplateProcessor(new NodeDefinition("template", "builtin-template", Map.of("replacements", List.of(Map.of("id", "ids", "pattern", "[0-9]+", "replacement", "{id}")))), dependencies).process(context, c -> {});
        assertEquals("orderId={id}", context.template());
        HighlightProcessor highlight = new HighlightProcessor(new NodeDefinition("h", "builtin-highlight", Map.of("maxSeenTemplates", 2)), dependencies);
        for (int i = 0; i < 10; i++) { context.template("t" + i); highlight.process(context, c -> {}); }
        assertEquals(2, highlight.seenCount());
    }
    @Test void allFactoriesRejectUnknownFieldsAndBadParametersAtConstruction() {
        for (LogProcessorFactory factory : BuiltinFactories.all()) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(new NodeDefinition("n", factory.providerId(), Map.of("typo", true)), dependencies));
        }
        assertThrows(IllegalArgumentException.class, () -> fold(Map.of("groupBy", List.of("template"))));
        assertThrows(IllegalArgumentException.class, () -> new FieldExtractProcessor(new NodeDefinition("f", "builtin-field-extract", Map.of("rules", List.of(Map.of("field", "x", "pattern", "no-group")))), dependencies));
        assertThrows(IllegalArgumentException.class, () -> new TemplateProcessor(new NodeDefinition("t", "builtin-template", Map.of("replacements", List.of(Map.of("pattern", "x", "replacement", "$2")))), dependencies));
    }

    @Test void fieldExtractionUsesNamedGroupsSkipsUnmatchedOptionalGroupsAndAppliesOrderedOverrides() {
        LogContext context = event(1, LogLevel.INFO, "order {}", null, "trace-from-mdc");
        FieldExtractProcessor extract = new FieldExtractProcessor(new NodeDefinition("extract", "builtin-field-extract", Map.of(
                "mdcMapping", Map.of("orderId", "traceId", "trace", "traceId"),
                "rules", List.of(Map.of("field", "orderId", "pattern", "orderId=(?<value>O-[0-9]+)"),
                        Map.of("field", "optional", "pattern", "alias=(?<value>[^ ]+)?")))), dependencies);
        extract.process(context, next -> {});
        assertEquals("O-1", context.fields().get("orderId"), "message rules run after and override MDC mappings");
        assertEquals("trace-from-mdc", context.fields().get("trace"));
        assertFalse(context.fields().containsKey("optional"), "a matching regex with a null optional group adds no field");
    }

    @Test void templatePreferenceAndOrderedNormalizationRulesAreDeterministic() {
        List<Map<String, Object>> rules = List.of(
                Map.of("id", "order", "pattern", "O-[0-9]+", "replacement", "{order}"),
                Map.of("id", "placeholder", "pattern", "\\{order\\}", "replacement", "{id}"));
        LogContext preferred = event(1, LogLevel.INFO, "order {}", null, "trace");
        new TemplateProcessor(new NodeDefinition("template", "builtin-template", Map.of("replacements", rules)), dependencies)
                .process(preferred, next -> {});
        assertEquals("order {}", preferred.template(), "parameterized original templates bypass text normalization");
        assertTrue(preferred.decisions().isEmpty());

        LogContext normalized = event(2, LogLevel.INFO, "order {}", null, "trace");
        new TemplateProcessor(new NodeDefinition("template", "builtin-template", Map.of(
                "preferOriginalTemplate", false, "replacements", rules)), dependencies).process(normalized, next -> {});
        assertEquals("orderId={id}", normalized.template());
        assertEquals(List.of("order", "placeholder"), normalized.decisions().stream().map(RuleDecision::ruleId).toList());
    }

    @Test void highlightEvictsOldestTemplateAndCanDisableFirstOccurrenceIndependently() {
        HighlightProcessor highlight = new HighlightProcessor(new NodeDefinition("highlight", "builtin-highlight",
                Map.of("maxSeenTemplates", 2, "keywords", List.of("Failure"))), dependencies);
        LogContext firstA = event(1, LogLevel.INFO, "A", "Failure", "trace"); firstA.template("A");
        highlight.process(firstA, next -> {});
        LogContext firstB = event(2, LogLevel.INFO, "B", null, "trace"); firstB.template("B");
        highlight.process(firstB, next -> {});
        LogContext repeatedA = event(3, LogLevel.INFO, "A", null, "trace"); repeatedA.template("A");
        highlight.process(repeatedA, next -> {});
        assertFalse(repeatedA.tags().contains("first-occurrence"));
        LogContext newC = event(4, LogLevel.INFO, "C", null, "trace"); newC.template("C");
        highlight.process(newC, next -> {});
        LogContext evictedA = event(5, LogLevel.INFO, "A", null, "trace"); evictedA.template("A");
        highlight.process(evictedA, next -> {});
        assertTrue(evictedA.tags().contains("first-occurrence"));
        assertEquals(2, highlight.seenCount());
        assertTrue(firstA.tags().containsAll(List.of("error", "keyword", "first-occurrence")));

        HighlightProcessor noFirst = new HighlightProcessor(new NodeDefinition("highlight-off", "builtin-highlight",
                Map.of("firstOccurrence", false, "keywords", List.of("Failure"))), dependencies);
        LogContext exception = event(6, LogLevel.INFO, "never marked first", "Failure", "trace");
        noFirst.process(exception, next -> {});
        assertFalse(exception.tags().contains("first-occurrence"));
        assertTrue(exception.tags().containsAll(List.of("error", "keyword")));
        assertEquals(0, noFirst.seenCount());
    }

    @Test void zeroFoldSamplesStillProducesACompleteAggregateAndSourceRange() {
        RepeatFoldProcessor fold = fold(Map.of("maxSamplesPerGroup", 0));
        feed(fold, event(1, LogLevel.INFO, "same {}", null, "trace"));
        feed(fold, event(2, LogLevel.INFO, "same {}", null, "trace"));
        fold.flush(new FlushContext(Long.MAX_VALUE, clock.wall, "STOP", true), context -> output.add(context.copy()));
        assertEquals(1, output.size());
        assertEquals(2, output.get(0).repeatCount());
        assertTrue(output.get(0).sourceRefs().sampleEventIds().isEmpty());
        assertEquals(1, output.get(0).sourceRefs().firstCaptureSequence());
        assertEquals(2, output.get(0).sourceRefs().lastCaptureSequence());
        assertEquals(List.of(1L, 2L), indexed);
    }
}
