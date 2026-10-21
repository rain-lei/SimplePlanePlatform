package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.ProcessedRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Uses a private rebuilt index so opening history never mutates old files or stale SQLite state. */
public final class HistoryRepository implements RecordRepository {
    private final SqliteIndex index;
    private final Path scratchDirectory;
    private final Map<String, Object> metadata;
    private final JsonlRecovery.Result recovery;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    public HistoryRepository(Path sessionDirectory, Path scratchRoot) throws IOException, SQLException {
        Path directory = sessionDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("session directory missing");
        scratchDirectory = scratchRoot.toAbsolutePath().normalize().resolve("history-" + UUID.randomUUID());
        Files.createDirectories(scratchDirectory);
        Path indexFile = scratchDirectory.resolve("index.sqlite");
        recovery = JsonlRecovery.rebuild(directory, indexFile);
        index = new SqliteIndex(directory, indexFile, false);
        Map<String, Object> loaded = new LinkedHashMap<>();
        Path sessionFile = directory.resolve("session.json");
        if (Files.isRegularFile(sessionFile)) {
            if (Files.size(sessionFile) > 1_048_576) throw new IOException("session metadata exceeds maximum");
            @SuppressWarnings("unchecked") Map<String, Object> raw = JsonCodec.MAPPER.readValue(Files.readAllBytes(sessionFile), Map.class);
            loaded.putAll(raw);
        } else { loaded.put("application", "recovered-session"); loaded.put("sessionId", directory.getFileName().toString()); loaded.put("complete", false); }
        boolean complete = Boolean.TRUE.equals(loaded.get("complete")) && recovery.incompleteTails() == 0;
        loaded.put("complete", complete); loaded.put("status", complete ? "COMPLETE" : "INCOMPLETE");
        loaded.put("readOnly", true); loaded.put("recovery", recovery);
        loaded.put("capturedCount", recovery.captured()); loaded.put("recordSequence", index.watermark());
        if (!complete) loaded.put("notice", "会话未完整结束；显示已有脱敏明细，不推断未生成的汇总。");
        metadata = java.util.Collections.unmodifiableMap(loaded);
    }
    public JsonlRecovery.Result recovery() { return recovery; }
    @Override public Map<String, Object> session() { return metadata; }
    @Override public Map<String, Object> configSummary() {
        Object config = metadata.get("config");
        if (!(config instanceof Map<?, ?>)) return Map.of();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) config;
        return java.util.Collections.unmodifiableMap(result);
    }
    @Override public Page<ProcessedRecord> records(RecordQuery query) { return index.records(query); }
    @Override public ProcessedRecord record(String id) { return index.record(id); }
    @Override public Sources sources(String id, long after, int limit) { ProcessedRecord record = record(id); return record == null ? null : index.sources(record, after, limit); }
    @Override public Page<CapturedLog> captured(long after, int limit) { return index.captured(after, limit); }
    @Override public long watermark() { return index.watermark(); }
    @Override public long telemetryWatermark() { return index.telemetryWatermark(); }
    @Override public Page<io.loglens.api.TelemetryEvent> telemetry(TelemetryQuery query) { return index.telemetry(query); }
    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        index.close();
        // Resolve and verify every target before deleting only this private recovery directory.
        Path verified = scratchDirectory.toAbsolutePath().normalize();
        if (!verified.getFileName().toString().startsWith("history-")) throw new IOException("unsafe recovery directory");
        try (var files = Files.walk(verified)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.toAbsolutePath().normalize().startsWith(verified)) throw new IOException("unsafe scratch cleanup");
                Files.deleteIfExists(file);
            }
        }
    }
}
