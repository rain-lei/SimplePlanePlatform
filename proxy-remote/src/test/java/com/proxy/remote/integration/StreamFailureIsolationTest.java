package com.proxy.remote.integration;

import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.exchange.header.DefaultFuture;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.exchange.header.HeaderExchangeClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class StreamFailureIsolationTest {
    private static final class StubClient implements Client {
        long closedStream;
        int sends;
        public void send(ProxyMessage message) { sends++; }
        public void close() { }
        public boolean isAvailable() { return true; }
        public int getActiveStreamCount() { return 0; }
        public void closeStream(long streamId) { closedStream = streamId; }
    }

    private ProxyMessage connect(long id) {
        return ProxyMessage.builder().type(ProxyMessage.MessageType.CONNECT).streamId(id).build();
    }

    @Test
    void browserCloseCancelsPendingConnectAndItsTimer() {
        StubClient transport = new StubClient();
        ExchangeHandler handler = new ExchangeHandler();
        HeaderExchangeClient client = new HeaderExchangeClient(transport, new URL("proxy", "localhost", 1), handler);
        io.netty.channel.embedded.EmbeddedChannel browser = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelInboundHandlerAdapter());
        java.util.concurrent.ConcurrentHashMap<Long, io.netty.channel.ChannelHandlerContext> registry =
                new java.util.concurrent.ConcurrentHashMap<>();
        registry.put(8L, browser.pipeline().lastContext());
        client.setStreamRegistry(registry);
        int before = DefaultFuture.getActiveFutureCount();
        try {
            CompletableFuture<?> connecting = client.request(connect(8), 30000);
            assertFalse(connecting.isDone());
            browser.close();
            assertTrue(connecting.isCompletedExceptionally());
            assertEquals(8, transport.closedStream);
            assertEquals(before, DefaultFuture.getActiveFutureCount());
        } finally {
            client.close();
            browser.finishAndReleaseAll();
        }
    }

    @Test
    void alreadyClosedBrowserDoesNotCreateTransportStream() {
        StubClient transport = new StubClient();
        ExchangeHandler handler = new ExchangeHandler();
        HeaderExchangeClient client = new HeaderExchangeClient(transport, new URL("proxy", "localhost", 1), handler);
        io.netty.channel.embedded.EmbeddedChannel browser = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelInboundHandlerAdapter());
        java.util.concurrent.ConcurrentHashMap<Long, io.netty.channel.ChannelHandlerContext> registry =
                new java.util.concurrent.ConcurrentHashMap<>();
        registry.put(9L, browser.pipeline().lastContext());
        client.setStreamRegistry(registry);
        try {
            browser.close();
            assertTrue(client.request(connect(9), 30000).isCompletedExceptionally());
            assertEquals(0, transport.sends);
        } finally {
            client.close();
            browser.finishAndReleaseAll();
        }
    }

    @Test
    void streamFailureOnlyFailsItsOwnPendingRequest() {
        StubClient first = new StubClient();
        StubClient second = new StubClient();
        ExchangeHandler firstHandler = new ExchangeHandler();
        HeaderExchangeClient a = new HeaderExchangeClient(first, new URL("proxy", "localhost", 1), firstHandler);
        HeaderExchangeClient b = new HeaderExchangeClient(second, new URL("proxy", "localhost", 2), new ExchangeHandler());
        int before = DefaultFuture.getActiveFutureCount();
        try {
            CompletableFuture<?> a1 = a.request(connect(1), 30000);
            CompletableFuture<?> a2 = a.request(connect(2), 30000);
            CompletableFuture<?> b1 = b.request(connect(3), 30000);
            firstHandler.onStreamClosed(1, new IllegalStateException("stream reset"));
            assertTrue(a1.isCompletedExceptionally());
            assertFalse(a2.isDone());
            assertFalse(b1.isDone());
            assertEquals(1, first.closedStream);
            firstHandler.onDisconnected();
            assertTrue(a2.isCompletedExceptionally());
            assertFalse(b1.isDone());
        } finally {
            a.close();
            b.close();
        }
        assertEquals(before, DefaultFuture.getActiveFutureCount());
    }
}
