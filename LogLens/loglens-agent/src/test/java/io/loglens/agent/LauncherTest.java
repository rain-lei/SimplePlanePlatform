package io.loglens.agent;

import io.loglens.launcher.LogLensAgent;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {
    @Test void parsesStrictPrefixAndPreservesCommasAndSpacesInConfigPath() {
        String path = "configs/folder with spaces,a.yaml";
        assertEquals(Path.of(path).toAbsolutePath().normalize().toString(), LogLensAgent.configPath("strict=true,config=" + path));
        assertEquals(Path.of(path).toAbsolutePath().normalize().toString(), LogLensAgent.configPath("config=" + path));
        assertThrows(IllegalArgumentException.class, () -> LogLensAgent.configPath(null));
        assertThrows(IllegalArgumentException.class, () -> LogLensAgent.configPath("config="));
        assertThrows(IllegalArgumentException.class, () -> LogLensAgent.configPath("unknown=true"));
    }
}
