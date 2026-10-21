package io.loglens.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir Path directory;
    @Test void configuredStrictWrapsRuntimeInitializationFailure() throws Exception {
        Path config = directory.resolve("strict.yaml");
        Files.writeString(config, "schemaVersion: 1\nagent:\n  failurePolicy: STRICT\n  pluginsDirectory: ./plugins\nweb:\n  enabled: false\n");
        Files.createDirectory(directory.resolve("plugins"));
        Files.writeString(directory.resolve("plugins/broken.jar"), "not a jar");
        assertThrows(AgentRuntime.StrictInitializationFailure.class, () -> AgentRuntime.start(config.toString(), null));
    }
    @Test void invalidConfigurationFailsBeforeInstrumentationAndRuntimeStartup() throws Exception {
        Path config = directory.resolve("invalid.yaml");
        Files.writeString(config, "schemaVersion: 99\n");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntime.start(config.toString(), null));
        assertFalse(Files.exists(directory.resolve("logs")));
    }
}
