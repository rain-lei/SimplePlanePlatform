package io.loglens.storage;

import io.loglens.api.CapturedLog;
import io.loglens.api.ProcessedRecord;
import io.loglens.api.TelemetryEvent;
import java.util.List;
import java.util.Map;

public interface RecordRepository extends AutoCloseable {
    record Page<T>(List<T> items, long nextCursor, boolean hasMore) { public Page { items = List.copyOf(items); } }
    record Sources(Page<CapturedLog> page, boolean expired) {}
    Map<String, Object> session();
    Map<String, Object> configSummary();
    Page<ProcessedRecord> records(RecordQuery query);
    ProcessedRecord record(String id);
    Sources sources(String id, long after, int limit);
    Page<CapturedLog> captured(long after, int limit);
    Page<TelemetryEvent> telemetry(TelemetryQuery query);
    long telemetryWatermark();
    long watermark();
    @Override default void close() throws Exception {}
}
