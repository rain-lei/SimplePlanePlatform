package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {
    @TempDir Path directory;
    @Test void pluginProducedSourceValuesCannotBypassOuterRedaction() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\nredaction:\n  messageRules:\n    - {id: token, pattern: '(token=)[^ ]+', replacement: '$1***'}\n");
        Redactor redactor = new Redactor(ConfigLoader.load(yaml));
        LogContext input = new LogContext(new CapturedLog("e", "s", 1, 1, 1, 1, LogLevel.INFO,
                "l", "t", "{}", "safe", List.of(), Map.of(), null, List.of(), false));
        input.aggregation(1, 1, 1, new SourceRefs(List.of("template"), List.of("token=private"), 1, 1, List.of("token=private")), "STOP");
        LogContext result = redactor.output(input);
        assertEquals(List.of("token=***"), result.sourceRefs().groupValues());
        assertEquals(List.of("token=***"), result.sourceRefs().sampleEventIds());
        assertEquals(List.of("token=private"), input.sourceRefs().groupValues());
    }
}
