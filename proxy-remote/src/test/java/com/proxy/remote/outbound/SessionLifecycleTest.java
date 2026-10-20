package com.proxy.remote.outbound;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleTest {
    private OutboundSession session(EmbeddedChannel inbound, long id) {
        return new OutboundSession(inbound.pipeline().lastContext(), "localhost", 80, String.valueOf(id), id);
    }

    @Test
    void closedSessionRejectsAndClosesLateTargetConnection() {
        EmbeddedChannel inbound = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel outbound = new EmbeddedChannel();
        try {
            OutboundSession session = session(inbound, 1);
            session.close();
            assertFalse(session.setOutboundChannel(outbound));
            assertFalse(outbound.isOpen());
            assertFalse(session.awaitActive(100));
        } finally {
            inbound.finishAndReleaseAll();
            outbound.finishAndReleaseAll();
        }
    }

    @Test
    void closingInboundImmediatelyReleasesTargetAndRegistry() {
        EmbeddedChannel inbound = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel outbound = new EmbeddedChannel();
        SessionManager manager = new SessionManager(100);
        try {
            OutboundSession session = session(inbound, 2);
            manager.register("2", session);
            assertTrue(session.setOutboundChannel(outbound));
            inbound.close();
            assertEquals(0, manager.activeCount());
            assertFalse(outbound.isOpen());
        } finally {
            manager.closeAll();
            inbound.finishAndReleaseAll();
            outbound.finishAndReleaseAll();
        }
    }

    @Test
    void wheelReapsDeadTargetButPreservesHealthyIdleSession() throws Exception {
        EmbeddedChannel deadInbound = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel idleInbound = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel deadOutbound = new EmbeddedChannel();
        EmbeddedChannel idleOutbound = new EmbeddedChannel();
        SessionManager manager = new SessionManager(100);
        try {
            OutboundSession dead = session(deadInbound, 3);
            OutboundSession idle = session(idleInbound, 4);
            manager.register("3", dead);
            manager.register("4", idle);
            assertTrue(dead.setOutboundChannel(deadOutbound));
            assertTrue(idle.setOutboundChannel(idleOutbound));
            // No OutboundHandler here: deliberately omit the normal target-close callback.
            deadOutbound.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((manager.get("3") != null || deadInbound.isOpen()) && System.nanoTime() < deadline) {
                deadInbound.runPendingTasks();
                Thread.sleep(10);
            }
            deadInbound.runPendingTasks();
            assertNull(manager.get("3"));
            assertFalse(deadInbound.isOpen());
            assertSame(idle, manager.get("4"));
            assertTrue(idleInbound.isActive());
            assertTrue(idleOutbound.isActive());
        } finally {
            manager.closeAll();
            deadInbound.finishAndReleaseAll();
            idleInbound.finishAndReleaseAll();
            deadOutbound.finishAndReleaseAll();
            idleOutbound.finishAndReleaseAll();
        }
    }
}
