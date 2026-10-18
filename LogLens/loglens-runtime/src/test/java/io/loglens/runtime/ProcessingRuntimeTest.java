package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import io.loglens.storage.HistoryRepository;
import io.loglens.storage.RecordQuery;
import io.loglens.storage.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingRuntimeTest {
    @TempDir Path directory;
    private static volatile CountDownLatch slowEntered = new CountDownLatch(0);
    private static volatile CountDownLatch slowRelease = new CountDownLatch(0);
    private static volatile CountDownLatch stuckEntered = new CountDownLatch(0);
    private static volatile CountDownLatch stuckRelease = new CountDownLatch(0);
    private static volatile CountDownLatch stuckClosed = new CountDownLatch(0);

    public static class DerivedSecretFactory implements LogProcessorFactory {
        @Override public String providerId() { return "runtime-test-derived"; }
        @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
            return new AbstractLogProcessor(definition, dependencies) {
                @Override public void process(LogContext context, ChainContinuation next) {
                    context.fields().put("customer", "private-customer-731");
                    context.fields().put("derived", "token=plugin-private-842");
                    next.proceed(context);
                }
            };
        }
    }

    public static class BlockingFactory implements LogProcessorFactory {
        @Override public String providerId() { return "runtime-test-blocking"; }
        @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
            return new AbstractLogProcessor(definition, dependencies) {
                private final AtomicBoolean first = new AtomicBoolean(true);
                @Override public void process(LogContext context, ChainContinuation next) {
                    if (first.compareAndSet(true, false)) {
                        slowEntered.countDown();
                        try {
                            if (!slowRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test processor interrupted", interrupted);
                        }
                    }
                    next.proceed(context);
                }
            };
        }
    }

    public static class StuckFactory implements LogProcessorFactory {
        @Override public String providerId() { return "runtime-test-stuck"; }
        @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
            return new AbstractLogProcessor(definition, dependencies) {
                @Override public void process(LogContext context, ChainContinuation next) {
                    stuckEntered.countDown();
                    try {
                        if (!stuckRelease.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test processor interrupted", interrupted);
                    }
                    next.proceed(context);
                }
                @Override public void close() { stuckClosed.countDown(); }
            };
        }
    }

    private void installDerivedSecretPlugin(Path plugins) throws Exception {
        installPlugin(plugins, DerivedSecretFactory.class);
    }

    private void installPlugin(Path plugins, Class<?> factory) throws Exception {
        Files.createDirectories(plugins);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("derived-secret.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/services/io.loglens.api.LogProcessorFactory"));
            jar.write((factory.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @Test void stopDrainsRedactedDetailsAndFlushesPendingAggregate() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, """
                schemaVersion: 1
                web:
                  enabled: false
                redaction:
                  messageRules:
                    - id: secret
                      pattern: '(token=)[^ ]+'
                      replacement: '$1***'
                runtime:
                  shutdownTimeoutMillis: 10000
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(runtime.publish(new CapturedLog("placeholder", "placeholder", 0,
                        100, 100, System.nanoTime(), LogLevel.INFO, "orders", "main",
                        "order {} token=private", "order 1 token=private", List.of("token=private"),
                        Map.of("token", "private"), null, List.of(), false)));
            }
        } finally { runtime.close(); }
        String captured = Files.readString(session.resolve("captured-0001.jsonl"));
        String processed = Files.readString(session.resolve("processed-0001.jsonl"));
        assertFalse(captured.contains("private"));
        assertFalse(processed.contains("private"));
        assertEquals(3, captured.lines().count());
        assertEquals(1, processed.lines().count());
        assertTrue(processed.contains("\"repeatCount\":3"));
        assertTrue(Files.readString(session.resolve("session.json")).contains("\"complete\":true"));
        assertFalse(runtime.publish(new CapturedLog("e", "s", 1, 1, 1, 1,
                LogLevel.ERROR, "l", "t", "x", "x", List.of(), Map.of(), null, List.of(), false)));
    }

    @Test void multiProducerPublishesAreDrainedBeforeStop() throws Exception {
        Path yaml = directory.resolve("multi-producer.yaml");
        Files.writeString(yaml, """
                schemaVersion: 1
                web:
                  enabled: false
                runtime:
                  ringBufferSize: 8192
                  overflowPolicy: DROP_NEWEST
                  shutdownTimeoutMillis: 10000
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        int producers = 4;
        int perProducer = 1000;
        var pool = Executors.newFixedThreadPool(producers);
        var ready = new CountDownLatch(producers);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, producers).mapToObj(producer -> pool.submit((java.util.concurrent.Callable<Void>) () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < perProducer; i++) {
                    CapturedLog event = new CapturedLog("placeholder", "placeholder", 0, 100, 100,
                            System.nanoTime(), LogLevel.INFO, "producer." + producer, "worker-" + producer,
                            "event {}", "event " + i, List.of(Integer.toString(i)), Map.of(), null, List.of(), false);
                    if (runtime.publish(event)) accepted.incrementAndGet();
                    else throw new AssertionError("unexpected drop with ring capacity above the total batch");
                }
                return null;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            runtime.close();
        }
        assertEquals(producers * perProducer, accepted.get());
        assertEquals(accepted.get(), Files.readAllLines(session.resolve("captured-0001.jsonl")).size());
        assertTrue(Files.readString(session.resolve("session.json")).contains("\"complete\":true"));
    }

    @Test void pluginDerivedSourceGroupsAreRedactedAndStillQueryable() throws Exception {
        Path yaml = directory.resolve("plugin-redaction.yaml");
        installDerivedSecretPlugin(directory.resolve("plugins"));
        Files.writeString(yaml, """
                schemaVersion: 1
                application: source-redaction
                agent:
                  pluginsDirectory: ./plugins
                web:
                  enabled: false
                redaction:
                  mdcKeys: [customer]
                  messageRules:
                    - id: token
                      pattern: '(token=)[^ ]+'
                      replacement: '$1***'
                pipeline:
                  - id: derive
                    provider: runtime-test-derived
                  - id: template
                    provider: builtin-template
                    config: {}
                  - id: fold
                    provider: builtin-repeat-fold
                    config:
                      groupBy: [logger, level, template, exceptionType, customer, derived]
                runtime:
                  shutdownTimeoutMillis: 10000
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        try {
            assertTrue(runtime.publish(new CapturedLog("placeholder", "placeholder", 0,
                    100, 100, System.nanoTime(), LogLevel.INFO, "orders", "main", "safe {}", "safe message",
                    List.of(), Map.of(), null, List.of(), false)));
        } finally { runtime.close(); }

        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            var record = history.records(RecordQuery.page(0, 10)).items().get(0);
            assertEquals(List.of("***", "token=***"), record.sourceRefs().groupValues().subList(4, 6));
            var sources = history.sources(record.recordId(), 0, 10);
            assertNotNull(sources);
            assertEquals(1, sources.page().items().size(), "the sanitized source-group index must still match its FINAL record");
        }
        try (var files = Files.walk(session)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(content.contains("private-customer-731"), file.getFileName() + " must not contain the raw plugin field");
                assertFalse(content.contains("plugin-private-842"), file.getFileName() + " must not contain the raw plugin field");
            }
        }
    }

    @Test void blockPublisherLeavesDuringShutdownAndAcceptedBatchDrains() throws Exception {
        slowEntered = new CountDownLatch(1);
        slowRelease = new CountDownLatch(1);
        Path yaml = directory.resolve("block-shutdown.yaml");
        installPlugin(directory.resolve("plugins"), BlockingFactory.class);
        Files.writeString(yaml, """
                schemaVersion: 1
                agent:
                  pluginsDirectory: ./plugins
                web:
                  enabled: false
                pipeline:
                  - id: slow
                    provider: runtime-test-blocking
                runtime:
                  ringBufferSize: 1024
                  overflowPolicy: BLOCK
                  shutdownTimeoutMillis: 10000
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        var pool = Executors.newFixedThreadPool(2);
        var blockedPublisher = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
        var closeFuture = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        var publisherStarted = new CountDownLatch(1);
        try {
            assertTrue(runtime.publish(event("first")));
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS), "consumer must be held inside the plugin");
            for (int i = 1; i < 1024; i++) assertTrue(runtime.publish(event("queued-" + i)));
            assertEquals(0L, runtime.health().get("remainingCapacity"));
            blockedPublisher.set(pool.submit(() -> { publisherStarted.countDown(); return runtime.publish(event("blocked")); }));
            assertTrue(publisherStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(blockedPublisher.get().isDone(), "BLOCK publisher must be waiting for ring capacity");

            closeFuture.set(pool.submit(() -> { runtime.close(); return null; }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.healthy() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(runtime.healthy(), "close must stop accepting before draining");
            slowRelease.countDown();
            assertFalse(blockedPublisher.get().get(5, TimeUnit.SECONDS), "blocked publication must leave when shutdown starts");
            closeFuture.get().get(10, TimeUnit.SECONDS);
        } finally {
            slowRelease.countDown();
            if (closeFuture.get() == null) runtime.close();
            pool.shutdownNow();
        }
        assertTrue(Files.readString(session.resolve("session.json")).contains("\"complete\":true"));
        assertEquals(1024, Files.readAllLines(session.resolve("captured-0001.jsonl")).size());
    }

    @Test void telemetryIsNonblockingAtRingCapacityAndStopDrainsAcceptedEvents() throws Exception {
        slowEntered = new CountDownLatch(1);
        slowRelease = new CountDownLatch(1);
        Path yaml = directory.resolve("telemetry-saturation.yaml");
        installPlugin(directory.resolve("plugins"), BlockingFactory.class);
        Files.writeString(yaml, """
                schemaVersion: 1
                agent:
                  pluginsDirectory: ./plugins
                web:
                  enabled: false
                pipeline:
                  - id: slow
                    provider: runtime-test-blocking
                runtime:
                  ringBufferSize: 1024
                  overflowPolicy: BLOCK
                  shutdownTimeoutMillis: 10000
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        var pool = Executors.newSingleThreadExecutor();
        var closeFuture = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            assertTrue(runtime.publish(event("hold-consumer")));
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS), "consumer must be held before telemetry fills the ring");
            int accepted = 0;
            for (int i = 0; i < 1023; i++) {
                if (!runtime.publishTelemetry(telemetryEvent(runtime, i))) break;
                accepted++;
            }
            assertEquals(1023, accepted, "telemetry fills the remaining bounded slots behind the active log");
            assertEquals(0L, runtime.health().get("remainingCapacity"));
            long overflowStarted = System.nanoTime();
            assertFalse(runtime.publishTelemetry(telemetryEvent(runtime, 1023)), "telemetry never blocks under BLOCK log policy");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - overflowStarted) < 500,
                    "a full telemetry ring must return promptly on the caller thread");
            assertEquals(1L, runtime.health().get("telemetryDropped"));
            assertEquals(1023L, runtime.health().get("telemetryAccepted"));

            closeFuture.set(pool.submit(() -> { runtime.close(); return null; }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.healthy() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(runtime.healthy(), "shutdown stops telemetry acceptance before draining");
            assertFalse(runtime.publishTelemetry(telemetryEvent(runtime, 1024)));
            assertEquals(2L, runtime.health().get("telemetryDropped"));
            slowRelease.countDown();
            closeFuture.get().get(10, TimeUnit.SECONDS);
        } finally {
            slowRelease.countDown();
            if (closeFuture.get() == null) runtime.close();
            pool.shutdownNow();
        }
        assertTrue(Files.readString(session.resolve("session.json")).contains("\"complete\":true"));
        assertTrue(Files.readString(session.resolve("session.json")).contains("\"telemetryCount\":1023"));
        List<String> lines = Files.readAllLines(session.resolve("telemetry-0001.jsonl"));
        assertEquals(1023, lines.size(), "STOP must persist every accepted telemetry item");
        TelemetryEvent first = JsonCodec.read(lines.get(0).getBytes(StandardCharsets.UTF_8), TelemetryEvent.class);
        TelemetryEvent last = JsonCodec.read(lines.get(lines.size() - 1).getBytes(StandardCharsets.UTF_8), TelemetryEvent.class);
        assertEquals(2, first.captureSequence());
        assertEquals(1024, last.captureSequence());
    }

    @Test void stuckPluginTimesOutAsIncompleteAndIsClosedAfterItReturns() throws Exception {
        stuckEntered = new CountDownLatch(1);
        stuckRelease = new CountDownLatch(1);
        stuckClosed = new CountDownLatch(1);
        Path yaml = directory.resolve("stuck-plugin.yaml");
        installPlugin(directory.resolve("plugins"), StuckFactory.class);
        Files.writeString(yaml, """
                schemaVersion: 1
                agent:
                  pluginsDirectory: ./plugins
                web:
                  enabled: false
                pipeline:
                  - id: stuck
                    provider: runtime-test-stuck
                runtime:
                  shutdownTimeoutMillis: 300
                """);
        ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system());
        Path session = runtime.storage().directory();
        boolean returned = false;
        try {
            assertTrue(runtime.publish(event("hold-consumer")));
            assertTrue(stuckEntered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            runtime.close();
            returned = true;
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 3000, "close must return after its bounded deadline");
            String metadata = Files.readString(session.resolve("session.json"));
            assertTrue(metadata.contains("\"complete\":false"));
            assertTrue(metadata.contains("\"status\":\"INCOMPLETE\""));
        } finally {
            stuckRelease.countDown();
            if (!returned) runtime.close();
        }
        assertTrue(stuckClosed.await(5, TimeUnit.SECONDS), "deferred cleanup closes the plugin after the consumer exits");
        Path probe = session.resolveSibling(session.getFileName() + ".close-probe");
        long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean storageClosed = false;
        while (!storageClosed && System.nanoTime() < cleanupDeadline) {
            try {
                Files.move(session, probe);
                Files.move(probe, session);
                storageClosed = true;
            } catch (java.io.IOException stillOpen) {
                Thread.sleep(25);
            }
        }
        assertTrue(storageClosed, "deferred cleanup must close SQLite and release every session file");
    }

    private TelemetryEvent telemetryEvent(ProcessingRuntime runtime, int sequence) {
        long now = System.currentTimeMillis();
        return new TelemetryEvent("telemetry-" + sequence, runtime.storage().sessionId(), 0, now, now,
                System.nanoTime(), TelemetryKind.TRAFFIC_SAMPLE, "connection-1", "stream-" + sequence,
                "127.0.0.1:1234", "http2", "example.test", 443, "active", null, sequence, sequence);
    }
    private CapturedLog event(String message) {
        return new CapturedLog("placeholder", "placeholder", 0, 100, 100, System.nanoTime(), LogLevel.INFO,
                "orders", "main", "{}", message, List.of(), Map.of(), null, List.of(), false);
    }
}
