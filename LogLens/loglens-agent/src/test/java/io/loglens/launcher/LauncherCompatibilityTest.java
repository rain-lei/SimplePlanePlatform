package io.loglens.launcher;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class LauncherCompatibilityTest {
    @Test void permitsJava17And21AndRejectsJava8WithActionableMessage() {
        assertNull(LogLensAgent.compatibilityError("17"));
        assertNull(LogLensAgent.compatibilityError("21"));
        assertEquals("Java 17 or newer is required; detected Java 1.8.", LogLensAgent.compatibilityError("1.8"));
        assertTrue(LogLensAgent.compatibilityError("11").contains("Java 17 or newer is required"));
        assertTrue(LogLensAgent.compatibilityError("unknown").contains("detected Java unknown"));
    }

    @Test void unsupportedRuntimeReportsAndDisablesMonitoringUnlessStrict() {
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertDoesNotThrow(() -> LogLensAgent.premain("config=unused.yaml", null, "1.8"));
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("COMPATIBILITY_ERROR"));
            assertTrue(output.contains("Monitoring disabled"));
            assertTrue(output.contains("detected Java 1.8"));
            assertThrows(IllegalStateException.class,
                    () -> LogLensAgent.premain("strict=true,config=unused.yaml", null, "1.8"));
        } finally {
            System.setErr(original);
        }
    }
}
