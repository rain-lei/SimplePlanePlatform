package io.loglens.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.loglens.config.LogLensConfig;
import io.loglens.api.TelemetryEvent;
import io.loglens.storage.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Read-only local API. Never reads the on-disk YAML as current runtime configuration. */
public final class LocalWebServer implements AutoCloseable {
    private final RecordRepository repository;
    private final LogLensConfig.Web settings;
    private final Supplier<Map<String, Object>> health;
    private final EventHub events;
    private final Supplier<java.util.List<TelemetryEvent>> activeTelemetry;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    public LocalWebServer(RecordRepository repository, LogLensConfig.Web settings,
                          Supplier<Map<String, Object>> health, EventHub events) throws IOException {
        this(repository,settings,health,events,java.util.List::of);
    }
    public LocalWebServer(RecordRepository repository, LogLensConfig.Web settings,
                          Supplier<Map<String, Object>> health, EventHub events,
                          Supplier<java.util.List<TelemetryEvent>> activeTelemetry) throws IOException {
        this.repository = repository; this.settings = settings; this.health = health; this.events = events; this.activeTelemetry=activeTelemetry;
        InetAddress address = InetAddress.getByName(settings.host());
        if (!address.isLoopbackAddress()) throw new IllegalArgumentException("loopback binding required");
        server = HttpServer.create(new InetSocketAddress(address, settings.port()), 64);
        int threads = settings.maxClients() + 4;
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
            Thread thread = new Thread(task, "loglens-http"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor); server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private boolean localRequest(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        try {
            URI requested = URI.create("http://" + host);
            String hostname = requested.getHost();
            if (hostname == null || !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(hostname)) return false;
            if (requested.getPort() != port()) return false;
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            return origin == null || origin.equals("http://" + host);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private void handle(HttpExchange exchange) throws IOException {
        boolean streaming = false;
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (!localRequest(exchange)) { json(exchange, 403, Map.of("error", "local origin required")); return; }
            if (!exchange.getRequestMethod().equals("GET")) { exchange.getResponseHeaders().set("Allow", "GET"); json(exchange, 405, Map.of("error", "read-only API")); return; }
            if (exchange.getRequestURI().toString().length() > 8192) { json(exchange, 414, Map.of("error", "URI too long")); return; }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            switch (path) {
                case "/", "/index.html", "/app.js", "/telemetry.js", "/style.css" -> {
                    only(query, "level", "logger", "q", "traceId", "orderId", "tag");
                    String resource = path.equals("/") ? "index.html" : path.substring(1);
                    try (var input = LocalWebServer.class.getResourceAsStream("/ui/" + resource)) {
                        if (input == null) { json(exchange, 404, Map.of("error", "UI resource missing")); return; }
                        byte[] bytes = input.readAllBytes();
                        String contentType = resource.endsWith(".js") ? "text/javascript" : resource.endsWith(".css") ? "text/css" : "text/html";
                        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
                        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                    }
                }
                case "/api/session" -> json(exchange, 200, repository.session());
                case "/api/config" -> json(exchange, 200, repository.configSummary());
                case "/api/health" -> json(exchange, 200, health.get());
                case "/api/records" -> {
                    only(query, "cursor", "through", "limit", "level", "logger", "tag", "traceId", "orderId", "q");
                    json(exchange, 200, repository.records(new RecordQuery(number(query, "cursor", 0), number(query, "through", Long.MAX_VALUE), limit(query),
                            query.get("level"), query.get("logger"), query.get("tag"), query.get("traceId"), query.get("orderId"), query.get("q"))));
                }
                case "/api/captured" -> { only(query, "cursor", "limit"); json(exchange, 200, repository.captured(number(query, "cursor", 0), limit(query))); }
                case "/api/telemetry/connections" -> { only(query); java.util.List<TelemetryEvent> active=activeTelemetry.get(); json(exchange,200,Map.of("items",active,"count",active.size())); }
                case "/api/telemetry/events", "/api/telemetry/traffic", "/api/telemetry/sessions" -> {
                    only(query,"cursor","through","limit","kind","connectionId","streamId","peerAddress","transport","target","status","fromTime","toTime");
                    String kind=query.get("kind");
                    if(path.endsWith("/traffic"))kind="TRAFFIC_SAMPLE";
                    json(exchange,200,repository.telemetry(new TelemetryQuery(number(query,"cursor",0),number(query,"through",Long.MAX_VALUE),limit(query),kind,
                            query.get("connectionId"),query.get("streamId"),query.get("peerAddress"),query.get("transport"),query.get("target"),query.get("status"),
                            number(query,"fromTime",0),number(query,"toTime",Long.MAX_VALUE))));
                }
                case "/api/events" -> {
                    only(query);
                    if (events == null) { json(exchange, 409, Map.of("error", "history session has no live stream")); return; }
                    streaming = true; stream(exchange);
                }
                default -> {
                    if (path.startsWith("/api/records/")) {
                        String suffix = path.substring("/api/records/".length());
                        boolean sources = suffix.endsWith("/sources");
                        String id = sources ? suffix.substring(0, suffix.length() - 8) : suffix;
                        if (id.isBlank() || id.length() > 256 || id.contains("/")) throw new IllegalArgumentException("invalid record id");
                        Object result;
                        if (sources) { only(query, "cursor", "limit"); result = repository.sources(id, number(query, "cursor", 0), limit(query)); }
                        else { only(query); result = repository.record(id); }
                        json(exchange, result == null ? 404 : 200, result == null ? Map.of("error", "record not found") : result);
                    } else json(exchange, 404, Map.of("error", "not found"));
                }
            }
        } catch (IllegalArgumentException invalid) { if (!streaming) json(exchange, 400, Map.of("error", "invalid query")); }
        catch (StorageException unavailable) { if (!streaming) json(exchange, 503, Map.of("error", "storage unavailable")); }
        finally { exchange.close(); }
    }
    private void stream(HttpExchange exchange) throws IOException {
        EventHub.Subscription subscription;
        try { subscription = events.subscribe(); }
        catch (IllegalStateException full) { json(exchange, 503, Map.of("error", "live client capacity reached")); return; }
        try (subscription) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            output.write(("event: ready\ndata: {\"watermark\":" + subscription.watermark() + ",\"telemetryWatermark\":" + subscription.telemetryWatermark() + "}\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
            while (!subscription.closed()) {
                PublishedEvent event = subscription.pollEvent(1000);
                String frame = event == null ? "event: health\ndata: " + JsonCodec.string(health.get()) + "\n\n" : "id: " + event.type() + ":" + event.sequence() + "\nevent: " + event.type() + "\ndata: " + JsonCodec.string(event.payload()) + "\n\n";
                output.write(frame.getBytes(StandardCharsets.UTF_8)); output.flush();
            }
            if (subscription.gap()) { output.write("event: gap\ndata: {\"reason\":\"slow-client\"}\n\n".getBytes(StandardCharsets.UTF_8)); output.flush(); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private int limit(Map<String, String> query) {
        long value = number(query, "limit", Math.min(100, settings.maxQueryLimit()));
        if (value < 1 || value > settings.maxQueryLimit()) throw new IllegalArgumentException("limit out of range");
        return (int) value;
    }
    private static long number(Map<String, String> query, String key, long fallback) {
        String value = query.get(key); if (value == null) return fallback;
        long parsed = Long.parseLong(value); if (parsed < 0) throw new IllegalArgumentException("negative cursor"); return parsed;
    }
    private static void only(Map<String, String> query, String... keys) {
        Set<String> allowed = Set.of(keys);
        if (!allowed.containsAll(query.keySet())) throw new IllegalArgumentException("unknown query field");
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (value.length() > 512 || result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("invalid query fields");
        }
        return result;
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JsonCodec.bytes(value); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
