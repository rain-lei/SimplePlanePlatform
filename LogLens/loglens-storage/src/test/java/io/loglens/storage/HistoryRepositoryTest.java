package io.loglens.storage;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HistoryRepositoryTest {
    @TempDir Path directory;
    @Test void reconstructsSourcesAndTornTailWithoutWritingHistoricalDirectory() throws Exception {
        Path yaml = directory.resolve("config.yaml"); Files.writeString(yaml, "schemaVersion: 1\nweb:\n  enabled: false\n");
        Path session; String recordId;
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), LogClock.system())) {
            session = storage.directory(); recordId = storage.sessionId() + ":r:1";
            CapturedLog event = new CapturedLog("e", storage.sessionId(), 1, 1, 1, 1, LogLevel.INFO, "l", "t", "{}", "safe", List.of(), Map.of(), null, List.of(), false);
            storage.captured(event);
            List<String> by = List.of("template"), values = List.of("normalized");
            storage.associate(1, by, values); LogContext context = new LogContext(event);
            context.aggregation(1, 1, 1, new SourceRefs(by, values, 1, 1, List.of("e")), "STOP");
            storage.processed(ProcessedRecord.from(storage.sessionId(), 1, context));
            storage.finish(true, Map.of());
        }
        Files.writeString(session.resolve("captured-0001.jsonl"), "{\"eventId\":\"torn", StandardOpenOption.APPEND);
        Map<Path, byte[]> before = new LinkedHashMap<>();
        try (var files = Files.walk(session)) { for (Path file : files.filter(Files::isRegularFile).toList()) before.put(file, Files.readAllBytes(file)); }
        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            assertEquals(1, history.recovery().incompleteTails()); assertFalse((Boolean) history.session().get("complete"));
            assertEquals(1, history.captured(0, 100).items().size());
            assertEquals(1, history.sources(recordId, 0, 100).page().items().size());
            assertFalse(history.sources(recordId, 0, 100).expired());
            assertEquals("safe", history.record(recordId).message());
        }
        for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
    }
    @Test void rebuildsTelemetryHistoryAndPreservesTypedFilters() throws Exception {
        Path yaml=directory.resolve("telemetry.yaml"); Files.writeString(yaml,"schemaVersion: 1\napplication: telemetry-history\nweb:\n  enabled: false\n");
        Path session;
        try(SessionStorage storage=new SessionStorage(ConfigLoader.load(yaml),LogClock.system())) {
            session=storage.directory();
            TelemetryEvent event=new TelemetryEvent("te-1",storage.sessionId(),11,1000,1001,10,TelemetryKind.TRAFFIC_SAMPLE,
                    "conn-a","stream-7","/10.0.0.7:5312","http2","example.test",443,"active",null,1234,5678);
            storage.telemetry(event); storage.finish(true,Map.of());
            assertTrue(Files.exists(session.resolve("telemetry-0001.jsonl")));
        }
        try(HistoryRepository history=new HistoryRepository(session,directory.resolve("scratch-telemetry"))) {
            assertEquals(1,history.recovery().telemetry());
            assertEquals(11,history.telemetryWatermark());
            var result=history.telemetry(new TelemetryQuery(0,11,10,"TRAFFIC_SAMPLE",null,"stream-7","10.0.0.7","http2","example.test","active",0,Long.MAX_VALUE));
            assertEquals(1,result.items().size()); assertEquals(1234,result.items().get(0).uplinkBytes());
            assertEquals(5678,result.items().get(0).downlinkBytes());
        }
    }    @Test void indexRebuildDoesNotInventSummaryForCapturedOnlySession() throws Exception {
        Path session = directory.resolve("session"); Files.createDirectory(session);
        CapturedLog event = new CapturedLog("e", "s", 1, 1, 1, 1, LogLevel.INFO, "l", "t", "{}", "captured-only", List.of(), Map.of(), null, List.of(), false);
        Files.writeString(session.resolve("captured-0001.jsonl"), JsonCodec.string(event) + "\n");
        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            assertFalse((Boolean) history.session().get("complete"));
            assertEquals(0, history.records(RecordQuery.page(0, 100)).items().size());
            assertEquals(1, history.captured(0, 100).items().size());
        }
    }
}
