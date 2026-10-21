package io.loglens.viewer;

import io.loglens.api.*;
import io.loglens.storage.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TextExporterTest {
    @TempDir Path directory;
    @Test void reexportsFinalTextFromHistoryAndRefusesOverwrite() throws Exception {
        Path yaml = directory.resolve("config.yaml"); Files.writeString(yaml, "schemaVersion: 1\nweb:\n  enabled: false\n");
        Path session;
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), LogClock.system())) {
            session = storage.directory();
            CapturedLog event = new CapturedLog("e", storage.sessionId(), 1, 1, 1, 1, LogLevel.WARN,
                    "orders", "main", "message", "恢复明细", List.of(), Map.of(), null, List.of(), false);
            storage.captured(event); storage.processed(ProcessedRecord.from(storage.sessionId(), 1, new LogContext(event)));
            storage.finish(true, Map.of());
        }
        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            Path output = directory.resolve("restored.log"); TextExporter.export(history, output);
            assertEquals(Files.readString(session.resolve("app-0001.log")), Files.readString(output));
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> TextExporter.export(history, output));
        }
    }
}
