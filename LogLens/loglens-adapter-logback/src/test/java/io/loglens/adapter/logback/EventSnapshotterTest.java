package io.loglens.adapter.logback;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class EventSnapshotterTest {
    @TempDir Path directory;
    public static class Event {
        final Map<String,String> mdc = new HashMap<>(Map.of("traceId", "original"));
        public String getLoggerName() { return "orders"; }
        public String getLevel() { return "INFO"; }
        public String getFormattedMessage() { return "x".repeat(100); }
        public String getMessage() { return "order {}"; }
        public Object[] getArgumentArray() { return new Object[]{new Object() { public String toString() { throw new IllegalStateException(); } }}; }
        public Map<String,String> getMDCPropertyMap() { return mdc; }
        public Object getThrowableProxy() { return null; }
        public long getTimeStamp() { return 10; }
        public String getThreadName() { return "producer"; }
        public Object[] getCallerData() { throw new AssertionError("caller capture must be off"); }
    }
    @Test void copiesMdcBoundsMessageIsolatesParameterConversionAndDoesNotReadCaller() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\ncapture:\n  maxMessageChars: 64\n");
        EventSnapshotter snapshotter = new EventSnapshotter(ConfigLoader.load(yaml).capture(), LogClock.system());
        Event event = new Event(); CapturedLog snapshot = snapshotter.snapshot(event);
        event.mdc.put("traceId", "changed");
        assertEquals("original", snapshot.mdc().get("traceId"));
        assertEquals(64, snapshot.message().length()); assertTrue(snapshot.truncated());
        assertEquals("[string conversion failed]", snapshot.parameters().get(0));
        assertEquals("producer", snapshot.thread()); assertEquals(10, snapshot.eventTime());
        assertTrue(snapshot.caller().isEmpty());
    }
}
