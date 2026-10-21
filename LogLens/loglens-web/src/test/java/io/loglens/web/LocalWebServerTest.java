package io.loglens.web;

import io.loglens.config.ConfigLoader;
import io.loglens.storage.SessionStorage;
import io.loglens.api.LogClock;
import io.loglens.api.CapturedLog;
import io.loglens.api.LogContext;
import io.loglens.api.LogLevel;
import io.loglens.api.ProcessedRecord;
import io.loglens.api.TelemetryEvent;
import io.loglens.api.TelemetryKind;
import io.loglens.storage.TelemetryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalWebServerTest {
    private record WireSse(Socket socket, InputStream chunkedBody) implements AutoCloseable {
        @Override public void close() throws Exception { socket.close(); }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream input;
        private int remaining;
        private boolean consumeTerminator;
        private boolean finished;
        ChunkedInputStream(InputStream input) { this.input = input; }
        private boolean prepareChunk() throws java.io.IOException {
            if (finished) return false;
            if (consumeTerminator) {
                if (input.read() != '\r' || input.read() != '\n') throw new java.io.IOException("invalid HTTP chunk terminator");
                consumeTerminator = false;
            }
            String sizeLine = readWireLine(input);
            int extension = sizeLine.indexOf(';');
            String size = extension < 0 ? sizeLine : sizeLine.substring(0, extension);
            remaining = Integer.parseInt(size.trim(), 16);
            if (remaining == 0) {
                while (!readWireLine(input).isEmpty()) { }
                finished = true;
                return false;
            }
            return true;
        }
        @Override public int read() throws java.io.IOException {
            if (remaining == 0 && !prepareChunk()) return -1;
            int value = input.read();
            if (value < 0) throw new java.io.IOException("truncated HTTP chunk");
            if (--remaining == 0) consumeTerminator = true;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
            if (length == 0) return 0;
            if (remaining == 0 && !prepareChunk()) return -1;
            int count = input.read(bytes, offset, Math.min(length, remaining));
            if (count < 0) throw new java.io.IOException("truncated HTTP chunk");
            remaining -= count;
            if (remaining == 0) consumeTerminator = true;
            return count;
        }
    }

    @TempDir Path directory;
    @Test void apiIsReadOnlyBoundedAndUsesLoadedConfiguration() throws Exception {
        Path yaml = directory.resolve("config.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: loaded\nweb:\n  port: 0\n");
        var config = ConfigLoader.load(yaml);
        try (SessionStorage storage = new SessionStorage(config, LogClock.system());
             EventHub hub = new EventHub(2, 1, 0);
             LocalWebServer server = new LocalWebServer(storage, config.web(), () -> Map.of("outputHealthy", true), hub)) {
            server.start(); Files.writeString(yaml, "invalid changed file");
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var ui = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ui.statusCode());
            assertTrue(ui.body().contains("日志工作台"));
            assertTrue(ui.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self'"));
            var script = client.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
            assertTrue(script.body().contains("textContent"));
            assertFalse(script.body().contains("innerHTML"));
            var telemetryScript = client.send(HttpRequest.newBuilder(URI.create(base + "/telemetry.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, telemetryScript.statusCode());
            assertTrue(telemetryScript.body().contains("telemetryReady"));
            assertFalse(telemetryScript.body().contains("innerHTML"));
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/AGENTS.md")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var session = client.send(HttpRequest.newBuilder(URI.create(base + "/api/session")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, session.statusCode()); assertTrue(session.body().contains("loaded"));
            assertFalse(session.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            var configResult = client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(configResult.body().contains(config.configHash()));
            for (String suffix : new String[]{"/api/records?limit=1001", "/api/records?cursor=-1", "/api/records?typo=x", "/api/records?limit=1&limit=2"}) {
                assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + suffix)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(base + "/api/config")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/api/session")).header("Origin", "https://example.com").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/api/records/missing")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void sseClientLimitRejectsExtraStreamAndReleasesSlotAfterDisconnect() throws Exception {
        Path yaml = directory.resolve("sse-limit.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: sse-limit\nweb:\n  port: 0\n  maxClients: 1\n  clientQueueSize: 1\n");
        var config = ConfigLoader.load(yaml);
        try (SessionStorage storage = new SessionStorage(config, LogClock.system());
             EventHub hub = new EventHub(1, 1, 0);
             LocalWebServer server = new LocalWebServer(storage, config.web(), () -> Map.of("accepting", true), hub)) {
            server.start();
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/api/events");
            var first = client.send(HttpRequest.newBuilder(endpoint).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            InputStream firstBody = first.body();
            readReady(firstBody);
            assertEquals(1, hub.clients());

            var rejected = client.send(HttpRequest.newBuilder(endpoint).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            firstBody.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hub.clients() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, hub.clients(), "disconnect must release the bounded SSE subscription");

            var replacement = client.send(HttpRequest.newBuilder(endpoint).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, replacement.statusCode());
            readReady(replacement.body());
            replacement.body().close();
        }
    }

    @Test void telemetryGapCanRecoverFromHistoryThroughReconnectWatermark() throws Exception {
        Path yaml = directory.resolve("telemetry-gap.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: telemetry-gap\nweb:\n  port: 0\n  clientQueueSize: 1\n  maxClients: 2\n");
        var config = ConfigLoader.load(yaml);
        try (SessionStorage storage = new SessionStorage(config, LogClock.system());
             EventHub hub = new EventHub(1, 2, storage.watermark(), storage.telemetryWatermark());
             LocalWebServer server = new LocalWebServer(storage, config.web(), () -> Map.of(), hub)) {
            server.start();
            try (var slow = hub.subscribe()) {
                assertEquals(0, slow.telemetryWatermark());
                for (int sequence = 1; sequence <= 2; sequence++) {
                    long now = System.currentTimeMillis();
                    TelemetryEvent event = new TelemetryEvent("te-" + sequence, storage.sessionId(), sequence,
                            now, now, System.nanoTime(), TelemetryKind.TRAFFIC_SAMPLE, "conn", "stream-" + sequence,
                            "127.0.0.1:1234", "http2", "example.test", 443, "active", null, sequence, sequence);
                    storage.telemetry(event);
                    hub.publishTelemetry(event);
                }
                assertTrue(slow.gap(), "the stalled subscriber must be told its bounded queue overflowed");
                assertTrue(slow.closed());
                assertEquals(0, hub.clients());

                try (var resumed = hub.subscribe()) {
                    assertEquals(2, resumed.telemetryWatermark(), "reconnect ready watermark bounds history catch-up");
                    HttpClient client = HttpClient.newHttpClient();
                    String base = "http://127.0.0.1:" + server.port();
                    var history = client.send(HttpRequest.newBuilder(URI.create(base + "/api/telemetry/events?cursor=0&through=" + resumed.telemetryWatermark() + "&limit=10")).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, history.statusCode());
                    assertTrue(history.body().contains("\"captureSequence\":1"));
                    assertTrue(history.body().contains("\"captureSequence\":2"));
                    var rows = storage.telemetry(new TelemetryQuery(0, resumed.telemetryWatermark(), 10,
                            null, null, null, null, null, null, null, 0, Long.MAX_VALUE));
                    assertEquals(2, rows.items().size(), "history query backfills every event lost to the slow subscriber");
                }
            }
        }
    }

    @Test void recordsApiHonorsInclusiveWatermarkAndExactLevelFilter() throws Exception {
        Path yaml = directory.resolve("records-api.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: api-query\nweb:\n  port: 0\n");
        var config = ConfigLoader.load(yaml);
        try (SessionStorage storage = new SessionStorage(config, LogClock.system());
             EventHub hub = new EventHub(4, config.web().maxClients(), 0);
             LocalWebServer server = new LocalWebServer(storage, config.web(), () -> Map.of(), hub)) {
            for (int sequence = 1; sequence <= 3; sequence++) {
                LogLevel level = sequence == 3 ? LogLevel.ERROR : LogLevel.INFO;
                CapturedLog event = new CapturedLog("event-" + sequence, storage.sessionId(), sequence,
                        sequence, sequence, sequence, level, "orders", "main", "order {}", "order " + sequence,
                        List.of(Integer.toString(sequence)), Map.of(), null, List.of(), false);
                storage.captured(event);
                storage.processed(ProcessedRecord.from(storage.sessionId(), sequence, new LogContext(event)));
            }
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            var through = client.send(HttpRequest.newBuilder(URI.create(base + "/api/records?cursor=1&through=2&limit=10")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, through.statusCode());
            assertTrue(through.body().contains("\"recordSequence\":2"));
            assertFalse(through.body().contains("\"recordSequence\":3"));
            var exactLevel = client.send(HttpRequest.newBuilder(URI.create(base + "/api/records?level=ERROR")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, exactLevel.statusCode());
            assertTrue(exactLevel.body().contains("\"recordSequence\":3"));
            assertFalse(exactLevel.body().contains("\"recordSequence\":1"));
            var captured = client.send(HttpRequest.newBuilder(URI.create(base + "/api/captured?cursor=0&limit=2")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, captured.statusCode());
            assertTrue(captured.body().contains("\"nextCursor\":2"));
            assertTrue(captured.body().contains("\"hasMore\":true"));
        }
    }

    @Test void slowTcpSseClientIsDroppedWithoutBlockingPublisherOrHealthyClient() throws Exception {
        Path yaml = directory.resolve("slow-socket.yaml");
        Files.writeString(yaml, "schemaVersion: 1\napplication: slow-socket\nweb:\n  port: 0\n  clientQueueSize: 16\n  maxClients: 2\n");
        var config = ConfigLoader.load(yaml);
        try (SessionStorage storage = new SessionStorage(config, LogClock.system());
             EventHub hub = new EventHub(16, 2, 0);
             LocalWebServer server = new LocalWebServer(storage, config.web(), () -> Map.of("accepting", true), hub)) {
            server.start();
            WireSse slow = openRawSse(server.port(), 1024);
            assertEquals(1, hub.clients());
            WireSse fast = openRawSse(server.port(), 64 * 1024);
            BufferedReader fastReader = new BufferedReader(new InputStreamReader(new ChunkedInputStream(fast.chunkedBody()), StandardCharsets.UTF_8));
            readReady(fastReader);
            AtomicInteger fastRecords = new AtomicInteger();
            var readerPool = java.util.concurrent.Executors.newSingleThreadExecutor();
            var fastRead = readerPool.submit(() -> {
                try {
                    String line;
                    while ((line = fastReader.readLine()) != null) if (line.startsWith("id: ")) fastRecords.incrementAndGet();
                } catch (java.io.IOException ignored) { }
            });
            String payload = "x".repeat(32 * 1024);
            long publishNanos = 0;
            long publishStarted = System.nanoTime();
            hub.publish(processed(1, payload));
            publishNanos += System.nanoTime() - publishStarted;
            long firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fastRecords.get() == 0 && System.nanoTime() < firstDeadline) Thread.sleep(5);
            assertTrue(fastRecords.get() > 0, "healthy client must receive the initial record");
            for (int sequence = 2; sequence <= 500; sequence++) {
                publishStarted = System.nanoTime();
                hub.publish(processed(sequence, payload));
                publishNanos += System.nanoTime() - publishStarted;
                Thread.sleep(5);
            }
            long publishMillis = TimeUnit.NANOSECONDS.toMillis(publishNanos);
            assertTrue(publishMillis < 1000, "publishing must remain independent of the slow TCP reader");

            long dropDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (hub.clients() != 1 && System.nanoTime() < dropDeadline) Thread.sleep(10);
            assertEquals(1, hub.clients(), "bounded overflow removes the non-reading socket but retains the draining client");
            long secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fastRecords.get() < 2 && System.nanoTime() < secondDeadline) Thread.sleep(5);
            assertTrue(fastRecords.get() >= 2, "the healthy socket continues receiving records");
            fast.close();
            slow.close();
            long clientsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hub.clients() != 0 && System.nanoTime() < clientsDeadline) Thread.sleep(10);
            assertEquals(0, hub.clients());
            readerPool.shutdownNow();
            fastRead.get(5, TimeUnit.SECONDS);
        }
    }

    private static WireSse openRawSse(int port, int receiveBufferBytes) throws Exception {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(receiveBufferBytes);
        socket.setSoTimeout(10000);
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
        OutputStream output = socket.getOutputStream();
        output.write(("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
        InputStream input = socket.getInputStream();
        String status = readWireLine(input);
        if (!status.contains(" 200 ")) throw new java.io.IOException("unexpected SSE response: " + status);
        String header;
        boolean chunked = false;
        while (!(header = readWireLine(input)).isEmpty()) if (header.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding: chunked")) chunked = true;
        if (!chunked) throw new java.io.IOException("SSE response is expected to use HTTP chunked framing");
        return new WireSse(socket, input);
    }

    private static String readWireLine(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (value < 0 && line.size() == 0) return "";
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static void readReady(BufferedReader reader) throws Exception {
        String line;
        boolean ready = false;
        while ((line = reader.readLine()) != null) {
            if (line.equals("event: ready")) ready = true;
            if (ready && line.isEmpty()) return;
        }
        fail("SSE stream closed before its ready watermark");
    }

    private static ProcessedRecord processed(long sequence, String message) {
        CapturedLog event = new CapturedLog("event-" + sequence, "session", sequence,
                sequence, sequence, sequence, LogLevel.INFO, "load", "socket", "large {}", message,
                List.of(), Map.of(), null, List.of(), false);
        return ProcessedRecord.from("session", sequence, new LogContext(event));
    }

    private static void readReady(InputStream body) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        boolean ready = false;
        while ((line = reader.readLine()) != null) {
            if (line.equals("event: ready")) ready = true;
            if (ready && line.isEmpty()) return;
        }
        fail("SSE stream closed before its ready watermark");
    }
}
