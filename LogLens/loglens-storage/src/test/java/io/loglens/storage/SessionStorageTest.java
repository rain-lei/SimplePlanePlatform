package io.loglens.storage;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionStorageTest {
    @TempDir Path directory;
    private SessionStorage storage() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: test\nweb:\n  enabled: false\n");
        return new SessionStorage(ConfigLoader.load(yaml), LogClock.system());
    }
    private SessionStorage storage(String output) throws Exception {
        Path yaml = directory.resolve("retention.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: retention\nweb:\n  enabled: false\n" + output);
        return new SessionStorage(ConfigLoader.load(yaml), LogClock.system());
    }
    private CapturedLog event(String session, long seq, LogLevel level) {
        return new CapturedLog(session + ":e:" + seq, session, seq, 100, 100, 100, level, "com.orders", "main", "订单 {}", "订单 orderId=O-" + seq, List.of("O-" + seq), Map.of("traceId", "T-1"), null, List.of(), false);
    }
    @Test void utf8OffsetsKeysetFiltersAndExactLevelFiles() throws Exception {
        try (SessionStorage storage = storage()) {
            for (int i = 1; i <= 3; i++) {
                CapturedLog event = event(storage.sessionId(), i, i == 3 ? LogLevel.ERROR : LogLevel.INFO);
                storage.captured(event); LogContext context = new LogContext(event);
                context.fields().put("orderId", "O-" + i); context.tags().add("first-occurrence");
                storage.processed(ProcessedRecord.from(storage.sessionId(), i, context));
            }
            var page = storage.records(RecordQuery.page(0, 2));
            assertEquals(2, page.items().size()); assertTrue(page.hasMore()); assertEquals(2, page.nextCursor());
            assertEquals(3, storage.records(RecordQuery.page(page.nextCursor(), 2)).items().get(0).recordSequence());
            assertEquals(1, storage.records(new RecordQuery(0, Long.MAX_VALUE, 100, "ERROR", null, null, null, null, null)).items().size());
            assertEquals(1, storage.records(new RecordQuery(0, Long.MAX_VALUE, 100, null, null, "first-occurrence", "T-1", "O-2", "订单")).items().size());
            assertEquals("订单 orderId=O-2", storage.record(storage.sessionId() + ":r:2").message());
            storage.flush();
            String errors = Files.readString(storage.directory().resolve("error-0001.log"));
            String infos = Files.readString(storage.directory().resolve("info-0001.log"));
            assertTrue(errors.contains("O-3")); assertFalse(errors.contains("O-1")); assertFalse(infos.contains("O-3"));
            assertFalse(Files.readString(storage.directory().resolve("session.json")).contains("replacement"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> activeSegments = (List<Map<String, Object>>) storage.session().get("segments");
            assertFalse(activeSegments.isEmpty());
            assertTrue(activeSegments.stream().anyMatch(segment -> "captured-0001.jsonl".equals(segment.get("file")) && Boolean.TRUE.equals(segment.get("active"))));
            for (Map<String, Object> segment : activeSegments) {
                Path file = storage.directory().resolve((String) segment.get("file"));
                assertEquals(Files.size(file), ((Number) segment.get("bytes")).longValue());
            }
            storage.finish(true, Map.of("dropped", 0L));
            assertEquals(true, storage.session().get("complete"));
            String metadata = Files.readString(storage.directory().resolve("session.json"));
            assertTrue(metadata.contains("\"segments\""));
            assertTrue(metadata.contains("captured-0001.jsonl"));
            assertTrue(metadata.contains("processed-0001.jsonl"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> finishedSegments = (List<Map<String, Object>>) storage.session().get("segments");
            assertTrue(finishedSegments.stream().noneMatch(segment -> Boolean.TRUE.equals(segment.get("active"))));
            for (Map<String, Object> segment : finishedSegments) {
                Path file = storage.directory().resolve((String) segment.get("file"));
                assertEquals(Files.size(file), ((Number) segment.get("bytes")).longValue());
            }
        }
    }
    @Test void aggregateSourcesUseActualGroupRatherThanAllEventsInRange() throws Exception {
        try (SessionStorage storage = storage()) {
            List<String> by = List.of("logger", "level", "template", "exceptionType");
            List<String> values = List.of("com.orders", "INFO", "normalized {}", "");
            CapturedLog first = null;
            for (int i = 1; i <= 3; i++) {
                CapturedLog event = event(storage.sessionId(), i, LogLevel.INFO); storage.captured(event);
                storage.associate(i, by, i == 2 ? List.of("com.orders", "INFO", "other {}", "") : values);
                if (first == null) first = event;
            }
            LogContext context = new LogContext(first);
            context.aggregation(2, 100, 100, new SourceRefs(by, values, 1, 3, List.of(first.eventId())), "STOP");
            storage.processed(ProcessedRecord.from(storage.sessionId(), 1, context));
            var sources = storage.sources(storage.sessionId() + ":r:1", 0, 1);
            assertFalse(sources.expired()); assertTrue(sources.page().hasMore());
            assertEquals(1, sources.page().items().get(0).captureSequence());
            assertEquals(3, storage.sources(storage.sessionId() + ":r:1", sources.page().nextCursor(), 1).page().items().get(0).captureSequence());
        }
    }
    @Test void storageFailureImmediatelyMakesTakeoverUnhealthy() throws Exception {
        try (SessionStorage storage = storage()) {
            Files.createDirectory(storage.directory().resolve("captured-0001.jsonl"));
            assertThrows(StorageException.class, () -> storage.captured(event(storage.sessionId(), 1, LogLevel.INFO)));
            assertFalse(storage.healthy());
            storage.finish(true, Map.of());
            assertEquals(false, storage.session().get("complete"));
        }
    }

    @Test void sqliteTagTransactionFailureKeepsJsonlAuthoritativeAndDoesNotBroadcast() throws Exception {
        Path sessionDirectory;
        String recordId;
        try (SessionStorage storage = storage()) {
            Path database = storage.directory().resolve("index.sqlite");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_tag_insert BEFORE INSERT ON tags " +
                        "BEGIN SELECT RAISE(ABORT, 'injected tag transaction failure'); END");
            }
            CapturedLog captured = event(storage.sessionId(), 1, LogLevel.ERROR);
            storage.captured(captured);
            LogContext context = new LogContext(captured);
            context.tags().add("transaction-must-rollback");
            ProcessedRecord record = ProcessedRecord.from(storage.sessionId(), 1, context);
            recordId = record.recordId();
            sessionDirectory = storage.directory();
            AtomicInteger broadcasts = new AtomicInteger();
            storage.broadcastTo(ignored -> broadcasts.incrementAndGet());

            assertThrows(StorageException.class, () -> storage.processed(record));
            assertFalse(storage.healthy(), "an index transaction error makes output unhealthy");
            assertEquals(0, storage.watermark(), "the failed record must not advance the published sequence");
            assertEquals(0, broadcasts.get(), "a record is not broadcast until file and index work succeed");
            assertTrue(storage.records(RecordQuery.page(0, 10)).items().isEmpty(), "SQLite must roll back the inserted record row");
            String authoritativeJsonl = Files.readString(storage.directory().resolve("processed-0001.jsonl"));
            assertTrue(authoritativeJsonl.contains(record.recordId()), "the preceding JSONL append remains authoritative for recovery");
            assertTrue(Files.readString(storage.directory().resolve("diagnostics.log")).contains("OUTPUT_WRITE_FAILED"));

            storage.finish(true, Map.of());
            assertEquals(false, storage.session().get("complete"), "partial persistence must never be marked complete");
        }
        try (HistoryRepository history = new HistoryRepository(sessionDirectory, directory.resolve("scratch-processed"))) {
            assertEquals(recordId, history.records(RecordQuery.page(0, 10)).items().get(0).recordId(),
                    "read-only recovery rebuilds the committed JSONL row despite the stale writer index");
            assertEquals(1, history.captured(0, 10).items().size());
            assertEquals(false, history.session().get("complete"));
        }
    }

    @Test void capturedSqliteInsertFailureKeepsSegmentAndDisablesFurtherOutput() throws Exception {
        Path sessionDirectory;
        String eventId;
        try (SessionStorage storage = storage()) {
            Path database = storage.directory().resolve("index.sqlite");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_captured_insert BEFORE INSERT ON captured " +
                        "BEGIN SELECT RAISE(ABORT, 'injected captured index failure'); END");
            }
            CapturedLog event = event(storage.sessionId(), 1, LogLevel.WARN);
            sessionDirectory = storage.directory();
            eventId = event.eventId();

            assertThrows(StorageException.class, () -> storage.captured(event));
            assertFalse(storage.healthy());
            assertTrue(storage.captured(0, 10).items().isEmpty(), "failed SQLite insert must leave no queryable capture row");
            assertTrue(Files.readString(storage.directory().resolve("captured-0001.jsonl")).contains(event.eventId()),
                    "the successfully appended captured segment remains available for index rebuild");
            assertTrue(Files.readString(storage.directory().resolve("diagnostics.log")).contains("CAPTURED_WRITE_FAILED"));
            storage.finish(true, Map.of());
            assertEquals(false, storage.session().get("complete"));
        }
        try (HistoryRepository history = new HistoryRepository(sessionDirectory, directory.resolve("scratch-captured"))) {
            assertEquals(eventId, history.captured(0, 10).items().get(0).eventId(),
                    "read-only recovery rebuilds capture lookup from the authoritative segment");
            assertEquals(false, history.session().get("complete"));
        }
    }
    @Test void segmentRotatesOnBytesAndDateAndNeverOverwritesEarlierFile() throws Exception {
        class Clock implements LogClock { long time; public long wallTimeMillis() { return time; } public long monotonicNanos() { return 0; } }
        Clock clock = new Clock();
        try (SegmentWriter writer = new SegmentWriter(directory, "captured", "jsonl", 8, clock)) {
            assertEquals("captured-0001.jsonl", writer.append("汉字".getBytes(java.nio.charset.StandardCharsets.UTF_8)).segment());
            assertEquals("captured-0002.jsonl", writer.append("abc".getBytes()).segment());
            clock.time = 86400000;
            assertEquals("captured-0003.jsonl", writer.append("d".getBytes()).segment());
            assertEquals(2, writer.closedSegments().size());
            assertEquals("汉字\n", Files.readString(directory.resolve("captured-0001.jsonl")));
        }
    }
    @Test void diskLimitDeletesClosedSegmentsAndTheirIndexButKeepsCurrentSegment() throws Exception {
        String output = "output:\n  text:\n    combined: false\n    splitByLevel: false\n  structured:\n    rotateSizeMiB: 1\n    maxDiskMiB: 1\n";
        try (SessionStorage storage = storage(output)) {
            String large = "x".repeat(12_000);
            for (int i = 1; i <= 150; i++) {
                CapturedLog base = event(storage.sessionId(), i, LogLevel.INFO);
                CapturedLog largeEvent = new CapturedLog(base.eventId(), base.sessionId(), base.captureSequence(), base.eventTime(), base.capturedAt(),
                        base.monotonicNanos(), base.level(), base.logger(), base.thread(), large, large, List.of(), base.mdc(), null, List.of(), false);
                storage.captured(largeEvent);
            }
            Path session = directory.resolve("logs/retention").resolve(storage.sessionId());
            List<Path> beforeCleanup;
            try (var files = Files.list(session)) { beforeCleanup = files.filter(path -> path.getFileName().toString().startsWith("captured-") && path.toString().endsWith(".jsonl")).sorted().toList(); }
            assertTrue(beforeCleanup.size() > 1, "large input must rotate into multiple segments");
            Path current = beforeCleanup.get(beforeCleanup.size() - 1);
            Path expired = beforeCleanup.get(0);
            assertTrue(Files.exists(current), "last segment should remain current after size rotation");
            assertTrue(Files.exists(expired), "closed segment should exist before cleanup");
            storage.cleanup();
            assertFalse(Files.exists(expired), "closed segment should be removed over disk limit");
            assertTrue(Files.exists(current), "cleanup must preserve the current segment");
            assertTrue(storage.captured(0, 100).items().stream().noneMatch(log -> log.captureSequence() == 1),
                    "removed segment rows must be removed from the index");
        }
    }

    @Test void retentionDaysDeletesExpiredClosedSegmentsAndTheirIndex() throws Exception {
        class Clock implements LogClock {
            long time = 1_000_000;
            public long wallTimeMillis() { return time; }
            public long monotonicNanos() { return TimeUnit.MILLISECONDS.toNanos(time); }
        }
        Clock clock = new Clock();
        Path yaml = directory.resolve("days-retention.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: retention\nweb:\n  enabled: false\noutput:\n  text:\n    retentionDays: 1\n");
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), clock)) {
            storage.captured(event(storage.sessionId(), 1, LogLevel.INFO));
            Path first = storage.directory().resolve("captured-0001.jsonl");
            assertTrue(Files.exists(first));
            clock.time += TimeUnit.DAYS.toMillis(2);
            storage.captured(event(storage.sessionId(), 2, LogLevel.INFO));
            assertTrue(Files.exists(first), "a segment younger than the cutoff remains available");
            clock.time += TimeUnit.DAYS.toMillis(2);
            storage.captured(event(storage.sessionId(), 3, LogLevel.INFO));
            assertFalse(Files.exists(first), "an expired closed segment is removed");
            assertTrue(Files.exists(storage.directory().resolve("captured-0003.jsonl")), "the active segment remains available");
            assertTrue(storage.captured(0, 10).items().stream().noneMatch(log -> log.captureSequence() == 1),
                    "expired segment rows are removed from the index");
        }
    }

    @Test void telemetryRetentionDeletesExpiredJsonlAndSQLiteRows() throws Exception {
        class Clock implements LogClock {
            long time = 1_000_000;
            public long wallTimeMillis() { return time; }
            public long monotonicNanos() { return TimeUnit.MILLISECONDS.toNanos(time); }
        }
        Clock clock = new Clock();
        Path yaml = directory.resolve("telemetry-retention.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: telemetry-retention\nweb:\n  enabled: false\noutput:\n  text:\n    retentionDays: 1\n");
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), clock)) {
            Path first = storage.directory().resolve("telemetry-0001.jsonl");
            storage.telemetry(telemetry(storage.sessionId(), 1, clock.time));
            assertTrue(Files.exists(first));
            clock.time += TimeUnit.DAYS.toMillis(2);
            storage.telemetry(telemetry(storage.sessionId(), 2, clock.time));
            assertTrue(Files.exists(first), "retention only removes closed segments after their close time ages out");
            clock.time += TimeUnit.DAYS.toMillis(2);
            storage.telemetry(telemetry(storage.sessionId(), 3, clock.time));
            assertFalse(Files.exists(first), "expired telemetry JSONL segment must be removed");
            assertTrue(Files.exists(storage.directory().resolve("telemetry-0003.jsonl")), "active telemetry segment must remain");
            var rows = storage.telemetry(new TelemetryQuery(0, 3, 10, null, null, null, null, null, null, null, 0, Long.MAX_VALUE));
            assertEquals(List.of(2L, 3L), rows.items().stream().map(TelemetryEvent::captureSequence).toList());
            assertEquals(3, storage.telemetryWatermark());
        }
    }

    private TelemetryEvent telemetry(String session, long sequence, long time) {
        return new TelemetryEvent("te-" + sequence, session, sequence, time, time, sequence,
                TelemetryKind.TRAFFIC_SAMPLE, "conn", "stream-" + sequence, "127.0.0.1:1", "http2",
                "example.test", 443, "active", null, sequence, sequence);
    }

    @Test void expiredGroupSegmentRemovesSourceAssociationsWhileCaptureRemains() throws Exception {
        class Clock implements LogClock {
            long time = 1_000_000;
            public long wallTimeMillis() { return time; }
            public long monotonicNanos() { return TimeUnit.MILLISECONDS.toNanos(time); }
        }
        Clock clock = new Clock();
        Path yaml = directory.resolve("group-retention.yaml");
        Files.writeString(yaml, """
                schemaVersion: 1
                application: group-retention
                web:
                  enabled: false
                output:
                  text:
                    combined: false
                    splitByLevel: false
                    retentionDays: 1
                  structured:
                    rotateSizeMiB: 1
                    maxDiskMiB: 20
                """);
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), clock)) {
            CapturedLog first = event(storage.sessionId(), 1, LogLevel.INFO);
            storage.captured(first);
            List<String> by = List.of("logger");
            List<String> values = List.of("com.orders");
            storage.associate(1, by, values);
            String large = "x".repeat(12_000);
            for (int i = 0; i < 100; i++) storage.associate(1, List.of("filler"), List.of(i + large));
            Path expiredGroups = storage.directory().resolve("groups-0001.jsonl");
            assertTrue(Files.exists(expiredGroups), "large association records must rotate the group segment");

            clock.time += TimeUnit.DAYS.toMillis(4);
            storage.captured(event(storage.sessionId(), 2, LogLevel.INFO));
            assertTrue(Files.exists(storage.directory().resolve("captured-0001.jsonl")),
                    "the captured segment is closed now and has not yet aged past retention");
            assertFalse(Files.exists(expiredGroups), "the older group segment must expire independently");

            LogContext context = new LogContext(first);
            context.aggregation(1, first.capturedAt(), first.capturedAt(),
                    new SourceRefs(by, values, 1, 1, List.of(first.eventId())), "STOP");
            String recordId = storage.sessionId() + ":r:1";
            storage.processed(ProcessedRecord.from(storage.sessionId(), 1, context));
            var remainingCaptured = storage.captured(0, 10).items();
            assertEquals(2, remainingCaptured.size());
            assertTrue(remainingCaptured.stream().anyMatch(log -> log.captureSequence() == 1), "the source capture remains available");
            var sources = storage.sources(recordId, 0, 10);
            assertNotNull(sources);
            assertTrue(sources.expired(), "the query must disclose that the source association expired");
            assertTrue(sources.page().items().isEmpty(), "expired group-index rows cannot point at retained captures");
        }
    }
}
