package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.LogLensConfig.ProcessorFailurePolicy;
import io.loglens.processors.RepeatFoldProcessor;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ChainRunnerTest {
    @Test void silentNormalDropIsCountedButWindowBufferIsNot() {
        ChainRunner dropped = runner(List.of(node("filter", (c, n) -> {})), ProcessorFailurePolicy.OUTPUT_FAILED);
        dropped.process(input());
        assertEquals(1, dropped.droppedCount());
        RepeatFoldProcessor fold = new RepeatFoldProcessor(new NodeDefinition("fold", "builtin-repeat-fold", Map.of()), deps);
        ChainRunner buffered = runner(List.of(fold), ProcessorFailurePolicy.OUTPUT_FAILED);
        buffered.process(input());
        assertEquals(0, buffered.droppedCount());
    }
    final List<LogContext> output = new ArrayList<>();
    final List<String> diagnostics = new ArrayList<>();
    final NodeDependencies deps = new NodeDependencies(LogClock.system(), (code, message) -> diagnostics.add(code));
    private LogContext input() {
        LogContext c = new LogContext(new CapturedLog("e", "s", 1, 1, 1, 1, LogLevel.INFO, "l", "t", "{}", "safe", List.of(), Map.of(), null, List.of(), false));
        c.fields().put("orderId", "original"); return c;
    }
    private LogProcessor node(String name, java.util.function.BiConsumer<LogContext, ChainContinuation> body) {
        return new LogProcessor() { public String nodeId() { return name; } public void process(LogContext c, ChainContinuation next) { body.accept(c, next); } };
    }
    private ChainRunner runner(List<LogProcessor> nodes, ProcessorFailurePolicy policy) { return new ChainRunner(nodes, c -> output.add(c.copy()), deps, policy, 10000); }
    @Test void nodeExceptionOutputsEntrySnapshotWithoutRepeatingPriorOrLaterNodes() {
        int[] calls = {0};
        ChainRunner runner = runner(List.of(node("first", (c, n) -> { calls[0]++; c.tags().add("first"); n.proceed(c); }),
                node("bad", (c, n) -> { c.fields().put("orderId", "corrupted"); throw new IllegalStateException(); }),
                node("last", (c, n) -> fail("later node must not run"))), ProcessorFailurePolicy.OUTPUT_FAILED);
        runner.process(input());
        assertEquals(1, calls[0]); assertEquals(1, output.size());
        assertEquals("original", output.get(0).fields().get("orderId"));
        assertTrue(output.get(0).processingFailed()); assertTrue(output.get(0).tags().contains("first"));
        assertEquals(1, runner.failedCount());
    }
    @Test void duplicateOrLateContinuationNeverDuplicatesOutput() {
        ChainRunner runner = runner(List.of(node("twice", (c, n) -> { n.proceed(c); n.proceed(c); })), ProcessorFailurePolicy.OUTPUT_FAILED);
        runner.process(input()); assertEquals(1, output.size()); assertEquals(1, runner.failedCount());
        AtomicReference<ChainContinuation> saved = new AtomicReference<>();
        ChainRunner late = runner(List.of(node("late", (c, n) -> saved.set(n))), ProcessorFailurePolicy.OUTPUT_FAILED);
        late.process(input());
        assertThrows(IllegalStateException.class, () -> saved.get().proceed(input()));
        assertEquals(1, output.size());
    }
    @Test void explicitlyConfiguredDropCountsFailuresWithoutOutput() {
        ChainRunner runner = runner(List.of(node("bad", (c, n) -> { throw new IllegalArgumentException(); })), ProcessorFailurePolicy.DROP);
        runner.process(input()); assertTrue(output.isEmpty()); assertEquals(1, runner.droppedCount());
    }
    @Test void foldFlushEntersOnlyDownstreamAndCanEmitSeveralRecords() {
        int[] upstream = {0}, downstream = {0};
        RepeatFoldProcessor fold = new RepeatFoldProcessor(new NodeDefinition("fold", "builtin-repeat-fold", Map.of()), deps);
        ChainRunner runner = runner(List.of(node("up", (c, n) -> { upstream[0]++; n.proceed(c); }), fold,
                node("down", (c, n) -> { downstream[0]++; n.proceed(c); })), ProcessorFailurePolicy.OUTPUT_FAILED);
        runner.process(input()); runner.process(input());
        assertEquals(0, output.size());
        runner.flush(new FlushContext(Long.MAX_VALUE, 1, "STOP", true));
        assertEquals(2, upstream[0]); assertEquals(1, downstream[0]); assertEquals(2, output.get(0).repeatCount());
        assertEquals(0, fold.groupCount());
    }
    @Test void outputFailuresPropagateWithoutBeingClassifiedAsPluginFailures() {
        ChainRunner runner = new ChainRunner(List.of(node("pass", (c, n) -> n.proceed(c))), c -> { throw new RuntimeException("disk"); }, deps, ProcessorFailurePolicy.OUTPUT_FAILED, 10000);
        assertThrows(ChainRunner.OutputFailure.class, () -> runner.process(input()));
        assertEquals(0, runner.failedCount());
    }
    @Test void failedFlushMarksIncompleteAndContinuesFlushingOtherNodes() {
        LogProcessor bad = new LogProcessor() {
            public String nodeId() { return "bad"; }
            public void process(LogContext c, ChainContinuation n) { n.proceed(c); }
            public void flush(FlushContext f, EventEmitter e) { throw new IllegalStateException(); }
        };
        ChainRunner runner = runner(List.of(bad), ProcessorFailurePolicy.OUTPUT_FAILED);
        runner.flush(new FlushContext(1, 1, "STOP", true));
        assertTrue(runner.incompleteFlush()); assertTrue(diagnostics.contains("FLUSH_FAILED"));
    }
}
