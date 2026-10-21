package io.loglens.runtime;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.loglens.api.*;
import io.loglens.config.LogLensConfig;
import io.loglens.storage.SessionStorage;
import io.loglens.web.EventHub;
import io.loglens.web.LocalWebServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded MULTI-producer/single-consumer runtime; no application objects enter this queue. */
public final class ProcessingRuntime implements AutoCloseable {
    private enum Kind { INVALID, LOG, TELEMETRY, TICK, STOP }
    private static final class Envelope {
        Kind kind = Kind.INVALID;
        CapturedLog event;
        TelemetryEvent telemetry;
        void clear() { kind = Kind.INVALID; event = null; telemetry = null; }
    }
    private final LogLensConfig config;
    private final LogClock clock;
    private final PluginRegistry plugins;
    private final SessionStorage storage;
    private final ChainRunner chain;
    private final Redactor redactor;
    private final Disruptor<Envelope> disruptor;
    private final RingBuffer<Envelope> ring;
    private final ScheduledExecutorService ticker;
    private EventHub events;
    private LocalWebServer web;
    private volatile java.util.function.Supplier<List<TelemetryEvent>> activeTelemetry = List::of;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean tickPending = new AtomicBoolean();
    private final AtomicInteger publishers = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong processedSequence = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong telemetryDropped = new AtomicLong();
    private final AtomicLong telemetryAccepted = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean shutdownComplete;
    private volatile boolean initializationFailed;
    private volatile Thread consumer;
    public ProcessingRuntime(LogLensConfig config, LogClock clock) throws Exception {
        this.config = config; this.clock = clock; redactor = new Redactor(config);
        AtomicReference<SessionStorage> output = new AtomicReference<>();
        DiagnosticSink diagnostics = (code, message) -> {
            SessionStorage target = output.get();
            if (target != null) target.report(code, message); else System.err.println("[LogLens] " + code + " " + message);
        };
        NodeDependencies dependencies = new NodeDependencies(clock, diagnostics, (sequence, by, values) -> {
            output.get().associate(sequence, redactor.sourceGroupBy(by), redactor.sourceGroupValues(by, values));
        });
        plugins = new PluginRegistry(config.agent().pluginsDirectory());
        List<LogProcessor> nodes;
        try { nodes = plugins.assemble(new ChainBuildContext(config.application(), config.pipeline()), dependencies); }
        catch (Throwable failure) {
            try { plugins.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try { storage = new SessionStorage(config, clock); }
        catch (Throwable failure) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                try { nodes.get(i).close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            try { plugins.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        output.set(storage);
        chain = new ChainRunner(nodes, context -> storage.processed(ProcessedRecord.from(storage.sessionId(), processedSequence.incrementAndGet(), redactor.output(context))),
                dependencies, config.runtime().processorFailurePolicy(), config.runtime().slowProcessorMillis());
        ThreadFactory consumerFactory = task -> {
            Thread thread = new Thread(task, "loglens-consumer"); thread.setDaemon(true); thread.setContextClassLoader(ProcessingRuntime.class.getClassLoader()); consumer = thread; return thread;
        };
        disruptor = new Disruptor<>(Envelope::new, config.runtime().ringBufferSize(), consumerFactory, ProducerType.MULTI, new BlockingWaitStrategy());
        disruptor.handleEventsWith(this::consume);
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<>() {
            public void handleEventException(Throwable ex, long sequence, Envelope event) { storage.fail("CONSUMER_FAILED", ex); event.clear(); }
            public void handleOnStartException(Throwable ex) { storage.fail("CONSUMER_START_FAILED", ex); }
            public void handleOnShutdownException(Throwable ex) { storage.fail("CONSUMER_STOP_FAILED", ex); }
        });
        ring = disruptor.start();
        ticker = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "loglens-tick"); thread.setDaemon(true); thread.setContextClassLoader(ProcessingRuntime.class.getClassLoader()); return thread; });
        ticker.scheduleAtFixedRate(this::tick, config.runtime().tickMillis(), config.runtime().tickMillis(), TimeUnit.MILLISECONDS);
        if (config.web().enabled()) {
            try {
                events = new EventHub(config.web().clientQueueSize(), config.web().maxClients(), storage.watermark(), storage.telemetryWatermark());
                storage.broadcastTo(events::publish);
                web = new LocalWebServer(storage, config.web(), this::health, events, this::activeTelemetry);
                web.start();
                storage.report("WEB_STARTED", "Local viewer port=" + web.port());
            } catch (Throwable failure) {
                initializationFailed = true;
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }
    public LogLensConfig config() { return config; }
    public SessionStorage storage() { return storage; }
    public int webPort() { return web == null ? -1 : web.port(); }
    public Redactor redactor() { return redactor; }
    public boolean healthy() { return accepting.get() && storage.healthy(); }
    public void activeTelemetry(java.util.function.Supplier<List<TelemetryEvent>> supplier) { activeTelemetry=java.util.Objects.requireNonNull(supplier); }
    private List<TelemetryEvent> activeTelemetry() { return activeTelemetry.get(); }
    public boolean publish(CapturedLog snapshot) {
        if (!accepting.get() || !storage.healthy()) return false;
        // Redaction/snapshot work must finish before reserving any ring slot.
        CapturedLog safe;
        try { safe = redactor.captured(snapshot); }
        catch (Throwable failure) { invalid.incrementAndGet(); storage.report("SNAPSHOT_FAILED", failure.getClass().getName()); return false; }
        publishers.incrementAndGet();
        long sequence;
        try {
            if (!accepting.get() || !storage.healthy()) return false;
            try {
                if (config.runtime().overflowPolicy() == LogLensConfig.OverflowPolicy.BLOCK) {
                    // Retry while accepting so BLOCK publishers can leave during shutdown.
                    for (;;) {
                        if (!accepting.get() || !storage.healthy()) return false;
                        try { sequence = ring.tryNext(); break; }
                        catch (InsufficientCapacityException full) { java.util.concurrent.locks.LockSupport.parkNanos(100000); }
                    }
                } else sequence = ring.tryNext();
            } catch (InsufficientCapacityException full) { dropped.incrementAndGet(); return false; }
            Envelope slot = ring.get(sequence); slot.clear();
            try {
                long captureSequence = sequence + 1;
                slot.event = new CapturedLog(storage.sessionId() + ":e:" + captureSequence, storage.sessionId(), captureSequence,
                        safe.eventTime(), safe.capturedAt(), safe.monotonicNanos(), safe.level(), safe.logger(), safe.thread(), safe.template(), safe.message(),
                        safe.parameters(), safe.mdc(), safe.exception(), safe.caller(), safe.truncated());
                slot.kind = Kind.LOG;
                return true;
            } catch (Throwable failure) { invalid.incrementAndGet(); return false; }
            finally { ring.publish(sequence); }
        } finally { publishers.decrementAndGet(); }
    }
    /** Nonblocking telemetry publication: never applies the log BLOCK overflow policy to proxy threads. */
    public boolean publishTelemetry(TelemetryEvent event) {
        if (!accepting.get() || !storage.healthy()) { telemetryDropped.incrementAndGet(); return false; }
        publishers.incrementAndGet();
        long sequence;
        try {
            if (!accepting.get() || !storage.healthy()) { telemetryDropped.incrementAndGet(); return false; }
            try { sequence=ring.tryNext(); }
            catch (InsufficientCapacityException full) { telemetryDropped.incrementAndGet(); return false; }
            Envelope slot=ring.get(sequence); slot.clear();
            try {
                slot.telemetry=event.withCaptureSequence(sequence+1);
                slot.kind=Kind.TELEMETRY;
                telemetryAccepted.incrementAndGet();
                return true;
            } catch(Throwable failure) { telemetryDropped.incrementAndGet(); return false; }
            finally { ring.publish(sequence); }
        } finally { publishers.decrementAndGet(); }
    }
    private void tick() {
        if (!accepting.get()) return;
        tickPending.set(true);
        publishControl(Kind.TICK);
    }
    private boolean publishControl(Kind kind) {
        long sequence;
        try { sequence = ring.tryNext(); } catch (InsufficientCapacityException full) { return false; }
        Envelope slot = ring.get(sequence); slot.clear();
        try { slot.kind = kind; } finally { ring.publish(sequence); }
        return true;
    }
    private void consume(Envelope slot, long sequence, boolean endOfBatch) {
        try {
            if (slot.kind == Kind.STOP) {
                try {
                    if (storage.healthy()) {
                        chain.flush(new FlushContext(clock.monotonicNanos(), clock.wallTimeMillis(), "STOP", true)); storage.flush();
                    }
                    shutdownComplete = storage.healthy() && !chain.incompleteFlush();
                } finally { stopped.countDown(); }
                return;
            }
            if (!storage.healthy()) return;
            if (slot.kind == Kind.LOG) {
                storage.captured(slot.event); captured.incrementAndGet();
                if (tickPending.getAndSet(false)) chain.flush(new FlushContext(clock.monotonicNanos(), clock.wallTimeMillis(), "TICK", false));
                chain.process(new LogContext(slot.event));
            } else if (slot.kind == Kind.TELEMETRY) {
                storage.telemetry(slot.telemetry);
                if (events != null) events.publishTelemetry(slot.telemetry);
            } else if (slot.kind == Kind.TICK && tickPending.getAndSet(false)) chain.flush(new FlushContext(clock.monotonicNanos(), clock.wallTimeMillis(), "TICK", false));
        } catch (Throwable failure) { storage.fail("CONSUMER_OUTPUT_FAILED", failure); }
        finally { slot.clear(); }
    }
    public Map<String, Object> health() {
        return Map.ofEntries(Map.entry("accepting", accepting.get()), Map.entry("outputHealthy", storage.healthy()),
                Map.entry("dropped", dropped.get()), Map.entry("invalid", invalid.get()),
                Map.entry("captured", captured.get()), Map.entry("recordSequence", processedSequence.get()),
                Map.entry("remainingCapacity", ring.remainingCapacity()), Map.entry("processorFailed", chain.failedCount()),
                Map.entry("processorDropped", chain.droppedCount()), Map.entry("incompleteFlush", chain.incompleteFlush()),
                Map.entry("telemetryDropped", telemetryDropped.get()), Map.entry("telemetryAccepted", telemetryAccepted.get()), Map.entry("telemetryWatermark", storage.telemetryWatermark()));
    }
    private Map<String, Long> counters() { return Map.of("dropped", dropped.get(), "invalid", invalid.get(), "captured", captured.get(), "processorFailed", chain.failedCount(), "processorDropped", chain.droppedCount(), "telemetryDropped", telemetryDropped.get(), "telemetryAccepted", telemetryAccepted.get()); }
    @Override public void close() throws Exception {
        if (!closing.compareAndSet(false, true)) return;
        accepting.set(false); ticker.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.runtime().shutdownTimeoutMillis());
        while (publishers.get() != 0 && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(100000);
        boolean controlSent = false;
        while (publishers.get() == 0 && !(controlSent = publishControl(Kind.STOP)) && System.nanoTime() < deadline) java.util.concurrent.locks.LockSupport.parkNanos(100000);
        boolean drained = controlSent && stopped.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (drained) {
            try { disruptor.shutdown(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (com.lmax.disruptor.TimeoutException timeout) { drained = false; }
        }
        if (!drained) {
            storage.report("SHUTDOWN_TIMEOUT", "Consumer did not finish before deadline; session is incomplete");
            storage.finish(false, counters()); disruptor.halt();
            // Do not close resources while a stuck plugin/consumer still owns them.
            Thread cleanup = new Thread(() -> {
                try { if (consumer != null) consumer.join(); finishResources(); }
                catch (Exception failure) { System.err.println("[LogLens] deferred cleanup failed: " + failure.getClass().getName()); }
            }, "loglens-deferred-cleanup"); cleanup.setDaemon(true); cleanup.start();
        } else { storage.finish(shutdownComplete && !initializationFailed, counters()); finishResources(); }
    }
    private void finishResources() throws Exception {
        if (events != null) events.close();
        if (web != null) web.close();
        chain.close();
        try { storage.close(); } finally { plugins.close(); }
    }
}
