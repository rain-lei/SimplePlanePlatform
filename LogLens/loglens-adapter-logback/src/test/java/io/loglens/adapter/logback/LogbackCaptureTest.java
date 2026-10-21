package io.loglens.adapter.logback;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import io.loglens.api.LogClock;
import io.loglens.config.ConfigLoader;
import io.loglens.runtime.ProcessingRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LogbackCaptureTest {
    @TempDir Path directory;
    @Test void directConsoleCanBeReplacedButAsyncConsoleFallsBackToObserve() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\nagent:\n  mode: replace-console\n  consoleAppenderNames: [CONSOLE]\nweb:\n  enabled: false\n");
        try (ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system())) {
            LoggerContext context = new LoggerContext();
            try {
                Logger logger = context.getLogger("orders"); logger.setAdditive(false);
                ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> console = new ConsoleAppender<>();
                console.setName("CONSOLE"); console.setContext(context); logger.addAppender(console);
                LogbackCapture capture = new LogbackCapture(runtime, LogClock.system());
                LoggingEvent event = new LoggingEvent(getClass().getName(), logger, Level.ERROR, "safe", null, null);
                event.setMDCPropertyMap(Map.of());
                assertTrue(capture.capture(logger, event));
                assertTrue(capture.compatibleConsole(console));
                logger.detachAppender(console);
                AsyncAppender wrapper = new AsyncAppender(); wrapper.setContext(context); wrapper.setName("ASYNC"); wrapper.addAppender(console);
                logger.addAppender(wrapper);
                assertFalse(capture.capture(logger, event));
                assertFalse(capture.compatibleConsole(wrapper));
            } finally { context.stop(); }
        }
    }
}
