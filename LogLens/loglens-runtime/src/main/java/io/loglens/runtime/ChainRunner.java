package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.LogLensConfig.ProcessorFailurePolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** All state mutation runs on the runtime's single consumer. */
public final class ChainRunner implements AutoCloseable {
    public static final class OutputFailure extends RuntimeException {
        public OutputFailure(Throwable cause) { super("pipeline output failed", cause); }
    }
    private final List<LogProcessor> nodes;
    private final EventEmitter terminal;
    private final NodeDependencies dependencies;
    private final ProcessorFailurePolicy failurePolicy;
    private final long slowNanos;
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean incompleteFlush;
    public ChainRunner(List<LogProcessor> nodes, EventEmitter terminal, NodeDependencies dependencies,
                       ProcessorFailurePolicy policy, int slowMillis) {
        this.nodes = List.copyOf(nodes); this.terminal = terminal; this.dependencies = dependencies;
        failurePolicy = policy; slowNanos = TimeUnit.MILLISECONDS.toNanos(slowMillis);
    }
    public void process(LogContext context) { run(0, context); }
    private void emitTerminal(LogContext context) {
        try { terminal.emit(context.copy()); }
        catch (OutputFailure failure) { throw failure; }
        catch (Throwable failure) { throw new OutputFailure(failure); }
    }
    private void run(int index, LogContext context) {
        Objects.requireNonNull(context, "continuation context");
        if (index == nodes.size()) { emitTerminal(context); return; }
        LogProcessor node = nodes.get(index);
        LogContext before = context.copy();
        long started = System.nanoTime();
        Thread owner = Thread.currentThread();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean proceeded = new AtomicBoolean(false);
        try {
            EventEmitter flushing = downstream -> {
                if (!active.get() || Thread.currentThread() != owner) throw new IllegalStateException("flush emitter must be synchronous");
                run(index + 1, downstream.copy());
            };
            node.beforeProcess(new FlushContext(context.event().monotonicNanos(), context.event().capturedAt(), "INPUT", false), context, flushing);
            node.process(context, downstream -> {
                if (!active.get() || Thread.currentThread() != owner) throw new IllegalStateException("continuation must be synchronous on consumer");
                if (!proceeded.compareAndSet(false, true)) throw new IllegalStateException("continuation already used");
                run(index + 1, downstream);
            });
            if (!proceeded.get()) {
                boolean buffered = context.decisions().stream().anyMatch(decision -> node.nodeId().equals(decision.nodeId()) && "BUFFER".equals(decision.action()));
                if (!buffered) {
                    dropped.incrementAndGet();
                    boolean explained = context.decisions().stream().anyMatch(decision -> node.nodeId().equals(decision.nodeId()) && "DROP".equals(decision.action()));
                    dependencies.diagnostics().report("PROCESSOR_DROP", node.nodeId() + (explained ? " explicit DROP" : " returned without continuation or BUFFER reason"));
                }
            }
        } catch (OutputFailure failure) { throw failure; }
        catch (Throwable failure) {
            failed.incrementAndGet();
            dependencies.diagnostics().report("PROCESSOR_FAILED", node.nodeId() + ": " + failure.getClass().getName());
            // Already emitted downstream output is not emitted a second time after a late exception.
            if (!proceeded.get()) {
                if (failurePolicy == ProcessorFailurePolicy.DROP) dropped.incrementAndGet();
                else {
                    before.processingFailed(true);
                    before.tags().add("processing-failed");
                    before.decisions().add(new RuleDecision(node.nodeId(), "exception", "OUTPUT_FAILED", failure.getClass().getName()));
                    emitTerminal(before);
                }
            }
        } finally {
            active.set(false);
            if (System.nanoTime() - started >= slowNanos) dependencies.diagnostics().report("SLOW_PROCESSOR", node.nodeId() + " exceeded " + TimeUnit.NANOSECONDS.toMillis(slowNanos) + "ms callback budget");
        }
    }
    public void flush(FlushContext flush) {
        for (int i = 0; i < nodes.size(); i++) {
            LogProcessor node = nodes.get(i);
            int downstreamIndex = i + 1;
            Thread owner = Thread.currentThread();
            AtomicBoolean active = new AtomicBoolean(true);
            try {
                node.flush(flush, context -> {
                    if (!active.get() || Thread.currentThread() != owner) throw new IllegalStateException("flush emitter must be synchronous");
                    run(downstreamIndex, context.copy());
                });
            } catch (OutputFailure failure) { throw failure; }
            catch (Throwable failure) {
                incompleteFlush = true;
                dependencies.diagnostics().report("FLUSH_FAILED", node.nodeId() + ": " + failure.getClass().getName());
            } finally { active.set(false); }
        }
    }
    public long failedCount() { return failed.get(); }
    public long droppedCount() { return dropped.get(); }
    public boolean incompleteFlush() { return incompleteFlush; }
    @Override public void close() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            try { nodes.get(i).close(); }
            catch (Throwable failure) { dependencies.diagnostics().report("NODE_CLOSE_FAILED", nodes.get(i).nodeId() + ": " + failure.getClass().getName()); }
        }
    }
}
