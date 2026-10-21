package io.loglens.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.loglens.api.LogClock;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RealLogbackSnapshotTest {
    @TempDir Path directory;
    @Test void realEventPreservesFormattedMessageMdcAndExceptionGraph() throws Exception {
        Path yaml = directory.resolve("config.yaml"); Files.writeString(yaml, "schemaVersion: 1\n");
        LoggerContext context = new LoggerContext(); try {
            IllegalStateException failure = new IllegalStateException("outer", new IllegalArgumentException("cause"));
            failure.addSuppressed(new UnsupportedOperationException("suppressed"));
            LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("orders"), Level.ERROR,
                    "order {} failed", failure, new Object[]{"O-1"});
            event.setMDCPropertyMap(Map.of("traceId", "T-1")); event.setThreadName("producer");
            var snapshot = new EventSnapshotter(ConfigLoader.load(yaml).capture(), LogClock.system()).snapshot(event);
            assertEquals("order O-1 failed", snapshot.message());
            assertEquals("order {} failed", snapshot.template());
            assertEquals("O-1", snapshot.parameters().get(0));
            assertEquals("T-1", snapshot.mdc().get("traceId"));
            assertEquals(IllegalStateException.class.getName(), snapshot.exception().type());
            assertEquals(IllegalArgumentException.class.getName(), snapshot.exception().cause().type());
            assertEquals(UnsupportedOperationException.class.getName(), snapshot.exception().suppressed().get(0).type());
            assertFalse(snapshot.exception().frames().isEmpty());
            assertFalse(snapshot.exception().frames().get(0).startsWith("at "));
            assertEquals("producer", snapshot.thread());
        } finally { context.stop(); }
    }
    @Test void frameBudgetIsSharedAcrossRealExceptionGraph() throws Exception {
        Path yaml = directory.resolve("bounded.yaml"); Files.writeString(yaml, "schemaVersion: 1\ncapture:\n  maxStackFrames: 1\n");
        LoggerContext context = new LoggerContext(); try {
            RuntimeException failure = new RuntimeException("outer", new RuntimeException("cause"));
            LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("orders"), Level.ERROR,
                    "failure", failure, null);
            event.setMDCPropertyMap(Map.of());
            var snapshot = new EventSnapshotter(ConfigLoader.load(yaml).capture(), LogClock.system()).snapshot(event);
            assertEquals(1, snapshot.exception().frames().size());
            assertTrue(snapshot.exception().cause().frames().isEmpty());
            assertTrue(snapshot.truncated());
        } finally { context.stop(); }
    }
}
