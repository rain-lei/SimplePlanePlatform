package io.loglens.viewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.loglens.api.*;
import io.loglens.storage.*;
import io.loglens.config.ConfigLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HtmlExporterTest {
    @TempDir Path directory;
    @Test void actualHistoryExportIsBoundedAndDoesNotOverwriteReport() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\nweb:\n  enabled: false\n");
        Path session;
        try (SessionStorage storage = new SessionStorage(ConfigLoader.load(yaml), LogClock.system())) {
            session = storage.directory();
            for (int i = 1; i <= 3; i++) {
                CapturedLog event = new CapturedLog("e" + i, storage.sessionId(), i, 1, 1, 1, LogLevel.INFO,
                        "logger", "thread", "message", "</script><img onerror=alert(1)>" + i, List.of(), Map.of(), null, List.of(), false);
                storage.captured(event); storage.processed(ProcessedRecord.from(storage.sessionId(), i, new LogContext(event)));
            }
            storage.finish(true, Map.of());
        }
        try (HistoryRepository history = new HistoryRepository(session, directory.resolve("scratch"))) {
            Path output = directory.resolve("report.html");
            HtmlExporter.export(history, output, 2, 100000);
            String html = Files.readString(output);
            assertFalse(html.contains("</script><img"));
            assertTrue(html.contains("\\u003c/script\\u003e"));
            assertTrue(html.contains("\"recordSequence\":2"));
            assertFalse(html.contains("\"recordSequence\":3"));
            assertTrue(html.contains("textContent"));
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> HtmlExporter.export(history, output, 2, 100000));
            Path tooSmall = directory.resolve("too-small.html");
            assertThrows(java.io.IOException.class, () -> HtmlExporter.export(history, tooSmall, 3, 1024));
            assertFalse(Files.exists(tooSmall));
        }
    }
    @Test void embeddedJsonCannotCloseScriptOrCreateMarkup() {
        String encoded = HtmlExporter.safeJson("{\"message\":\"</script><img src=x onerror=alert(1)>&\"}");
        assertFalse(encoded.contains("<")); assertFalse(encoded.contains(">"));
        assertFalse(encoded.contains("&")); assertTrue(encoded.contains("\\u003c/script\\u003e"));
    }
}
