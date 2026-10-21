package com.proxy.local.config;

import com.proxy.cluster.ClientInvoker;
import com.proxy.cluster.StickyClusterInvoker;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Filter;
import com.proxy.common.filter.FilterChainBuilder;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.local.handler.StreamChannelRegistry;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local assembly of manual HTTP/3 selection. Network establishment never runs on browser IO threads. */
public final class Http3NodeController implements Invoker, AutoCloseable {
    private final StickyClusterInvoker sticky = new StickyClusterInvoker();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "http3-node-selection");
        thread.setDaemon(true);
        return thread;
    });
    private ExchangeClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.Semaphore pendingConnects = new java.util.concurrent.Semaphore(128);
    private volatile ProxyConfig snapshot;
    private volatile String nodeStatus = "idle";

    public Http3NodeController(ProxyConfig config) {
        config.validate();
        snapshot = config;
    }

    public String getCurrentNodeId() { return snapshot.getNodeSelection().getSelectedNodeId(); }
    public int getBindingCount() { return sticky.getBindingCount(); }
    public String getNodeStatus() {
        if (closed.get()) return "stopped";
        return "available".equals(nodeStatus) && !sticky.isAvailable() ? "unavailable" : nodeStatus;
    }

    /** Serialized with connection creation. Unreachable nodes may be selected without reverting to the old node. */
    public CompletableFuture<Void> apply(ProxyConfig next) {
        next.validate();
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(() -> {
            if (!selectionKey(snapshot).equals(selectionKey(next))) {
                retireClient();
                nodeStatus = "idle";
            }
            snapshot = next;
            result.complete(null);
        }, result);
        return result;
    }

    private static List<Object> selectionKey(ProxyConfig config) {
        ProxyConfig.RemoteServer selected = config.getRemoteServers().stream()
                .filter(node -> node.getId().equals(config.getNodeSelection().getSelectedNodeId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected node missing"));
        return java.util.Arrays.asList(selected.getId(), selected.getHost(), selected.getPort(),
                selected.getTransport(), selected.isSsl(), selected.getCipher(), selected.getCipherKey(),
                new org.yaml.snakeyaml.Yaml().dump(selected.getHttp3()), config.getTimeoutMs());
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) {
        if (closed.get()) return CompletableFuture.completedFuture(Response.error("Node controller closed"));
        if (invocation.getType() != ProxyMessage.MessageType.CONNECT) return sticky.invoke(invocation);
        Object rawStreamId = invocation.getAttachment("streamId");
        final long streamId;
        try { streamId = Long.parseLong(String.valueOf(rawStreamId)); }
        catch (NumberFormatException invalid) { return CompletableFuture.completedFuture(Response.error("Invalid streamId")); }
        if (!pendingConnects.tryAcquire()) return CompletableFuture.completedFuture(Response.error("Local CONNECT queue full"));
        long started = System.nanoTime();
        CompletableFuture<Response> result = new CompletableFuture<>();
        result.whenComplete((response, failure) -> pendingConnects.release());
        io.netty.channel.ChannelHandlerContext browser = StreamChannelRegistry.getInstance().get(streamId);
        if (browser != null) browser.channel().closeFuture().addListener(ignored -> {
            // Established streams are disconnected by RelayHandler; only cancel pending CONNECT here.
            if (!result.completeExceptionally(new java.nio.channels.ClosedChannelException())) return;
            sticky.unbind(streamId);
            try {
                coordinator.execute(() -> {
                    if (client != null) client.getClient().closeStream(streamId);
                });
            } catch (java.util.concurrent.RejectedExecutionException stopped) { /* close() owns resource cleanup */ }
        });
        submit(() -> {
            if (result.isDone()) return;
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= snapshot.getTimeoutMs()) {
                result.complete(Response.error("CONNECT budget expired"));
                return;
            }
            if (client == null) createSelectedConnection(started + TimeUnit.MILLISECONDS.toNanos(snapshot.getTimeoutMs()));
            if (result.isDone()) return;
            long remaining = snapshot.getTimeoutMs() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (remaining <= 0) {
                result.complete(Response.error("CONNECT budget expired"));
                return;
            }
            invocation.setAttachment("requestTimeoutMs", remaining);
            sticky.invoke(invocation).whenComplete((response, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(response);
            });
        }, result);
        return result;
    }

    private void createSelectedConnection(long deadline) {
        nodeStatus = "connecting";
        ProxyConfig config = snapshot;
        ProxyConfig.RemoteServer selected = config.getRemoteServers().stream()
                .filter(node -> node.getId().equals(getCurrentNodeId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected node missing"));
        Exchanger exchanger = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension();
        FilterChainBuilder builder = ExtensionLoader.getLoader(FilterChainBuilder.class).getDefaultExtension();
        List<Filter> filters = ExtensionLoader.getLoader(Filter.class).getActivateExtensions("client");
        try {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new IllegalStateException("CONNECT budget expired during connection creation");
            URL url = new URL("proxy", selected.getHost(), selected.getPort())
                    .addParameter("transport", "http3")
                    .addParameter("ssl", String.valueOf(selected.isSsl()))
                    .addParameter("cipher", selected.getCipher())
                    .addParameter("cipherKey", selected.getCipherKey());
            selected.getHttp3().toUrlParameters(url);
            url.addParameter("http3.handshakeTimeoutMs", Math.min(remaining, selected.getHttp3().getHandshakeTimeoutMs()));
            client = exchanger.connect(url);
            client.setStreamRegistry(StreamChannelRegistry.getInstance().getInternalMap());
            client.setStreamClosedListener(sticky::unbind);
            sticky.setInvokers(Collections.singletonList(
                    builder.build(new ClientInvoker(client, config.getTimeoutMs()), filters)));
            nodeStatus = "available";
        } catch (RuntimeException failure) {
            nodeStatus = "unavailable";
            closeClient();
            throw failure;
        }
    }

    private void submit(Runnable action, CompletableFuture<?> result) {
        try {
            coordinator.execute(() -> {
                try {
                    if (closed.get()) throw new IllegalStateException("Node controller closed");
                    action.run();
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
    }

    private void closeClient() {
        ExchangeClient previous = client;
        client = null;
        if (previous != null) previous.close();
    }

    private void retireClient() {
        sticky.setInvokers(Collections.emptyList());
        try {
            // Let the remote release its sessions before closing the QUIC/UDP transport.
            sticky.retireBindings().get(Math.min(2000, snapshot.getTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // An unreachable remote must not prevent a manual switch; close locally below.
        } finally {
            closeClient();
        }
    }

    @Override public boolean isAvailable() { return !closed.get(); }
    @Override public int getActiveCount() { return sticky.getBindingCount(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            coordinator.execute(() -> {
                retireClient();
            });
            coordinator.shutdown();
            try {
                if (!coordinator.awaitTermination(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Node controller cleanup did not finish within 15 seconds");
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
