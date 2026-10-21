package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeWebTest {
    @TempDir Path directory;
    private static volatile CountDownLatch pluginClosed = new CountDownLatch(0);
    private static volatile boolean pluginCloseFails;

    public static class CloseTrackingFactory implements LogProcessorFactory {
        @Override public String providerId() { return "runtime-web-close-tracker"; }
        @Override public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
            return new AbstractLogProcessor(definition, dependencies) {
                @Override public void process(LogContext context, ChainContinuation next) { next.proceed(context); }
                @Override public void close() {
                    pluginClosed.countDown();
                    if (pluginCloseFails) throw new IllegalStateException("injected node close failure");
                }
            };
        }
    }

    private void installCloseTrackingPlugin(Path plugins) throws Exception {
        Files.createDirectories(plugins);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("close-tracker.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/services/io.loglens.api.LogProcessorFactory"));
            jar.write((CloseTrackingFactory.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @Test @Timeout(20) void portConflictFailsInitializationAndClosesRuntimeResources() throws Exception {
        pluginClosed = new CountDownLatch(1);
        pluginCloseFails = false;
        installCloseTrackingPlugin(directory.resolve("plugins"));
        try (java.net.ServerSocket occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Path yaml = directory.resolve("conflict.yaml");
            Files.writeString(yaml, """
                    schemaVersion: 1
                    agent:
                      pluginsDirectory: ./plugins
                    pipeline:
                      - id: close-tracker
                        provider: runtime-web-close-tracker
                    web:
                      port: %d
                    """.formatted(occupied.getLocalPort()));
            assertThrows(java.net.BindException.class, () -> new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system()));
            assertTrue(pluginClosed.await(5, TimeUnit.SECONDS), "plugins must close when web startup fails");
            Path output = directory.resolve("logs/application");
            try (var sessions = Files.list(output)) {
                Path session = sessions.findFirst().orElseThrow();
                String metadata = Files.readString(session.resolve("session.json"));
                assertTrue(metadata.contains("\"endedAt\":"));
                assertTrue(metadata.contains("\"complete\":false"));
                // Windows refuses this rename if the failed initialization left SQLite open.
                Path index = session.resolve("index.sqlite");
                Files.move(index, session.resolve("closed-index.sqlite"));
                Path relocated = session.resolveSibling(session.getFileName() + ".closed");
                Files.move(session, relocated);
                Files.move(relocated, session);
            }
        }
    }

    @Test @Timeout(20) void storageInitializationFailureClosesEveryPluginAndPreservesStartupFailure() throws Exception {
        pluginClosed = new CountDownLatch(1);
        pluginCloseFails = true;
        Path plugins = directory.resolve("plugins");
        installCloseTrackingPlugin(plugins);
        Path pluginJar = plugins.resolve("close-tracker.jar");
        Path outputParent = directory.resolve("output-parent");
        Files.writeString(outputParent, "not a directory");
        Path yaml = directory.resolve("storage-failure.yaml");
        Files.writeString(yaml, """
                schemaVersion: 1
                agent:
                  pluginsDirectory: ./plugins
                pipeline:
                  - id: close-tracker
                    provider: runtime-web-close-tracker
                output:
                  directory: ./output-parent/logs
                web:
                  enabled: false
                """);
        try {
            var startupFailure = assertThrows(java.io.IOException.class,
                    () -> new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system()));
            assertTrue(pluginClosed.await(5, TimeUnit.SECONDS), "the assembled plugin must be closed when storage setup fails");
            assertTrue(List.of(startupFailure.getSuppressed()).stream().anyMatch(failure ->
                    failure instanceof IllegalStateException && failure.getMessage().contains("injected node close failure")),
                    "cleanup failures must be attached to the original storage startup failure");
            Path movedJar = pluginJar.resolveSibling("close-tracker.closed");
            Files.move(pluginJar, movedJar);
            assertTrue(Files.exists(movedJar), "plugin classloader must close even if the node close callback fails");
            assertFalse(Files.exists(directory.resolve("output-parent/logs/application")));
        } finally {
            pluginCloseFails = false;
        }
    }
    @Test @Timeout(20) void persistedRecordArrivesOnRealSseAndCanBeQueriedAtItsSequence() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\nweb:\n  port: 0\n");
        try (ProcessingRuntime runtime = new ProcessingRuntime(ConfigLoader.load(yaml), LogClock.system())) {
            String base = "http://127.0.0.1:" + runtime.webPort();
            HttpClient client = HttpClient.newHttpClient();
            var stream = client.send(HttpRequest.newBuilder(URI.create(base + "/api/events")).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                assertEquals("event: ready", reader.readLine());
                assertEquals("data: {\"watermark\":0,\"telemetryWatermark\":0}", reader.readLine());
                assertEquals("", reader.readLine());
                assertTrue(runtime.publish(new CapturedLog("e", "s", 1, 1, 1, System.nanoTime(), LogLevel.ERROR,
                        "orders", "main", "failed", "failed", List.of(), Map.of(), null, List.of(), false)));
                String line; boolean recordEvent = false; String data = null;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("event: record")) recordEvent = true;
                    if (recordEvent && line.startsWith("data: ")) { data = line.substring(6); break; }
                }
                assertNotNull(data);
                assertTrue(data.contains("\"recordSequence\":1"));
                TelemetryEvent telemetry=new TelemetryEvent("te","s",0,2,2,System.nanoTime(),TelemetryKind.CLIENT_CONNECTED,
                        "conn-1",null,"127.0.0.1:1234","http2",null,0,"connected",null,0,0);
                assertTrue(runtime.publishTelemetry(telemetry));
                boolean telemetryEvent=false;
                while((line=reader.readLine())!=null){if(line.equals("event: telemetry"))telemetryEvent=true;if(telemetryEvent&&line.startsWith("data: ")){assertTrue(line.contains("\"CLIENT_CONNECTED\""));break;}}
                var telemetryPage=runtime.storage().telemetry(new io.loglens.storage.TelemetryQuery(0,Long.MAX_VALUE,10,null,null,null,null,null,null,null,0,Long.MAX_VALUE));
                assertEquals(1,telemetryPage.items().size());
                assertEquals("conn-1",telemetryPage.items().get(0).connectionId());
                var telemetryApi=client.send(HttpRequest.newBuilder(URI.create(base + "/api/telemetry/events?kind=CLIENT_CONNECTED&limit=10")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,telemetryApi.statusCode());
                assertTrue(telemetryApi.body().contains("conn-1"));
                assertTrue(telemetryApi.body().contains("CLIENT_CONNECTED"));
                var page = client.send(HttpRequest.newBuilder(URI.create(base + "/api/records?through=1")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode()); assertTrue(page.body().contains("\"message\":\"failed\""));
                var next = client.send(HttpRequest.newBuilder(URI.create(base + "/api/records?cursor=1")).build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(next.body().contains("\"items\":[]"));
            }
        }
    }
}
