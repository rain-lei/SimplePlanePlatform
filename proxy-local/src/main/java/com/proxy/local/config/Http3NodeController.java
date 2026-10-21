package com.proxy.local.config;

import com.proxy.cluster.ClientInvoker;
import com.proxy.cluster.StickyClusterInvoker;
import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Filter;
import com.proxy.common.filter.FilterChainBuilder;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.local.handler.StreamChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Owns node clients. Startup and config changes run here; browser traffic goes directly to the cluster. */
public final class Http3NodeController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Http3NodeController.class);
    private final StickyClusterInvoker cluster = new StickyClusterInvoker();
    private final Map<String, ClientInvoker> nodes = new HashMap<>();
    private volatile ProxyConfig snapshot;
    private volatile boolean closed;
    private volatile String nodeStatus = "connecting";

    public Http3NodeController(ProxyConfig config) {
        config.validate();
        snapshot = config;
        connectSelected();
    }

    public ClusterInvoker getClusterInvoker() { return cluster; }
    public String getCurrentNodeId() { return snapshot.getNodeSelection().getSelectedNodeId(); }
    public int getBindingCount() { return cluster.getBindingCount(); }
    public String getNodeStatus() {
        if (closed) return "stopped";
        return "available".equals(nodeStatus) && !cluster.isAvailable() ? "unavailable" : nodeStatus;
    }

    /** Called by the config watcher, never by a browser EventLoop. */
    public synchronized void apply(ProxyConfig next) {
        next.validate();
        if (closed) throw new IllegalStateException("Node controller closed");
        boolean changed = !selectionKey(snapshot).equals(selectionKey(next));
        if (changed) retireSelected();
        snapshot = next;
        if (changed) connectSelected();
    }

    private static List<Object> selectionKey(ProxyConfig config) {
        ProxyConfig.RemoteServer selected = selectedNode(config);
        return java.util.Arrays.asList(selected.getId(), selected.getHost(), selected.getPort(),
                selected.getTransport(), selected.isSsl(), selected.getCipher(), selected.getCipherKey(),
                new org.yaml.snakeyaml.Yaml().dump(selected.getHttp3()), config.getTimeoutMs());
    }

    private static ProxyConfig.RemoteServer selectedNode(ProxyConfig config) {
        return config.getRemoteServers().stream()
                .filter(node -> node.getId().equals(config.getNodeSelection().getSelectedNodeId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected node missing"));
    }

    private void connectSelected() {
        ProxyConfig.RemoteServer selected = selectedNode(snapshot);
        nodeStatus = "connecting";
        ExchangeClient client = null;
        try {
            URL url = new URL("proxy", selected.getHost(), selected.getPort())
                    .addParameter("transport", "http3")
                    .addParameter("ssl", String.valueOf(selected.isSsl()))
                    .addParameter("cipher", selected.getCipher())
                    .addParameter("cipherKey", selected.getCipherKey());
            selected.getHttp3().toUrlParameters(url);
            client = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension().connect(url);
            client.setStreamRegistry(StreamChannelRegistry.getInstance().getInternalMap());
            client.setStreamClosedListener(cluster::unbind);
            ClientInvoker invoker = new ClientInvoker(client, snapshot.getTimeoutMs(), selected.getId());
            FilterChainBuilder builder = ExtensionLoader.getLoader(FilterChainBuilder.class).getDefaultExtension();
            List<Filter> filters = ExtensionLoader.getLoader(Filter.class).getActivateExtensions("client");
            cluster.setInvokers(Collections.singletonList(builder.build(invoker, filters)));
            nodes.put(invoker.getNodeId(), invoker);
            nodeStatus = "available";
        } catch (RuntimeException failure) {
            if (client != null) client.close();
            nodeStatus = "unavailable";
            log.warn("Cannot connect to selected node {}", selected.getId(), failure);
        }
    }

    private void retireSelected() {
        cluster.setInvokers(Collections.emptyList());
        try {
            // Allow remote sessions to close before releasing the QUIC/UDP transport.
            cluster.retireBindings().get(Math.min(2000, snapshot.getTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            log.debug("Remote disconnect not acknowledged; closing node locally", failure);
        } finally {
            ClientInvoker previous = nodes.remove(getCurrentNodeId());
            if (previous != null) previous.close();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        retireSelected();
    }
}
