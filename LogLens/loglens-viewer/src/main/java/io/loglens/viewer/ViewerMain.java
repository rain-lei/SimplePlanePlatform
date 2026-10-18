package io.loglens.viewer;

import io.loglens.config.LogLensConfig;
import io.loglens.storage.HistoryRepository;
import io.loglens.web.LocalWebServer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Standalone read-only history server; never installs instrumentation or reprocesses old logs. */
public final class ViewerMain {
    private ViewerMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("export") || args[0].equals("export-text"))) {
            if (args.length != 4) throw new IllegalArgumentException("Usage: viewer export <session-directory> <output.html> <scratch-directory>");
            Path session = Path.of(args[1]).toAbsolutePath().normalize();
            Path output = Path.of(args[2]).toAbsolutePath().normalize();
            Path scratch = Path.of(args[3]).toAbsolutePath().normalize();
            if (scratch.startsWith(session) || output.startsWith(session)) throw new IllegalArgumentException("export output and scratch must be outside history session");
            try (HistoryRepository repository = new HistoryRepository(session, scratch)) {
                if (args[0].equals("export-text")) TextExporter.export(repository, output);
                else HtmlExporter.export(repository, output, 10000, 32 * 1024 * 1024);
            }
            System.out.println("LogLens report: " + output);
            return;
        }
        if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("Usage: viewer <session-directory> [port] [scratch-directory]");
        Path session = Path.of(args[0]).toAbsolutePath().normalize();
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 9527;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be 0..65535");
        Path scratch = args.length == 3 ? Path.of(args[2]).toAbsolutePath().normalize() : session.getParent().resolve(".viewer-cache");
        if (scratch.startsWith(session)) throw new IllegalArgumentException("scratch directory must be outside historical session");
        try (HistoryRepository repository = new HistoryRepository(session, scratch)) {
            var web = new LogLensConfig.Web(true, "127.0.0.1", port, 5000, 1000, 16, 500);
            try (LocalWebServer server = new LocalWebServer(repository, web,
                    () -> Map.of("readOnly", true, "outputHealthy", true, "complete", repository.session().get("complete")), null)) {
                CountDownLatch shutdown = new CountDownLatch(1);
                Thread hook = new Thread(() -> {
                    server.close();
                    try { repository.close(); }
                    catch (Exception failure) { System.err.println("[LogLens] viewer cleanup failed: " + failure.getClass().getName()); }
                    finally { shutdown.countDown(); }
                }, "loglens-viewer-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                server.start();
                System.out.println("LogLens history: http://127.0.0.1:" + server.port());
                try { shutdown.await(); }
                finally {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException exiting) { /* JVM shutdown is in progress. */ }
                }
            }
        }
    }
}
