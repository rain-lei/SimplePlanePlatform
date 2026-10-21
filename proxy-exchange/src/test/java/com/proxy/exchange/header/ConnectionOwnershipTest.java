package com.proxy.exchange.header;

import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionOwnershipTest {
    @Test void synchronousConnectCallbackWritesHandshakeBeforeFollowingData() {
        ExchangeHandler handler = new ExchangeHandler();
        EmbeddedChannel transport = new EmbeddedChannel(handler);
        EmbeddedChannel browser = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
        registry.put(104L, browser.pipeline().firstContext());
        handler.setStreamRegistry(registry);
        DefaultFuture connect = DefaultFuture.newFuture(90004, 10000);
        handler.trackRequest(90004, 104, connect, true);
        connect.whenComplete((response, error) -> browser.writeOutbound(
                io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{5, 0})));
        transport.writeInbound(ProxyMessage.builder().requestId(90004).streamId(104)
                .type(ProxyMessage.MessageType.CONNECT_RESPONSE).status(Response.OK).build());
        transport.writeInbound(ProxyMessage.builder().streamId(104).type(ProxyMessage.MessageType.DATA)
                .data(new byte[]{42}).build());
        io.netty.buffer.ByteBuf handshake = browser.readOutbound();
        io.netty.buffer.ByteBuf payload = browser.readOutbound();
        assertEquals(5, handshake.readByte());
        assertEquals(42, payload.readByte());
        handshake.release();
        payload.release();
        handler.onStreamClosed(104, null);
        transport.finishAndReleaseAll();
        browser.finishAndReleaseAll();
    }

    @Test void disconnectClosesOnlyOwnedEstablishedStreamsAndRequests() {
        ExchangeHandler a = new ExchangeHandler(), b = new ExchangeHandler();
        EmbeddedChannel browserA = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel browserB = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
        registry.put(101L, browserA.pipeline().firstContext());
        registry.put(102L, browserB.pipeline().firstContext());
        a.setStreamRegistry(registry);
        b.setStreamRegistry(registry);
        DefaultFuture established = DefaultFuture.newFuture(90001, 10000);
        DefaultFuture pendingB = DefaultFuture.newFuture(90002, 10000);
        a.trackRequest(90001, 101, established, true);
        b.trackRequest(90002, 102, pendingB, true);
        established.complete(Response.ok());
        a.onDisconnected();
        browserA.runPendingTasks();
        assertFalse(browserA.isActive());
        assertTrue(browserB.isActive());
        assertFalse(pendingB.isDone());
        assertEquals(1, registry.size());
        b.onDisconnected();
        assertTrue(pendingB.isCompletedExceptionally());
        browserA.finishAndReleaseAll();
        browserB.finishAndReleaseAll();
    }

}
