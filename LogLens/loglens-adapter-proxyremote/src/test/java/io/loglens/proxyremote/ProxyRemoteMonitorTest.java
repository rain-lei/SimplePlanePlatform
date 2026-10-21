package io.loglens.proxyremote;

import io.loglens.api.TelemetryEvent;
import io.loglens.api.TelemetryKind;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class ProxyRemoteMonitorTest {
    public static final class ChannelId { private final String value; ChannelId(String value) { this.value=value; } public String asLongText() { return value; } }
    public static final class Pipeline { public List<String> names() { return List.of("frameCodec", "multiplexHandler"); } }
    public static final class CloseFuture {
        volatile GenericFutureListener<Object> listener;
        public CloseFuture addListener(GenericFutureListener<Object> value) { listener=value; return this; }
        void fire() throws Exception { if (listener != null) listener.operationComplete(this); }
    }
    public static final class Channel {
        private final String id; private final Channel parent; final CloseFuture closeFuture=new CloseFuture();
        Channel(String id, Channel parent) { this.id=id; this.parent=parent; }
        public ChannelId id() { return new ChannelId(id); } public String remoteAddress() { return "/127.0.0.1:4231"; }
        public Pipeline pipeline() { return new Pipeline(); } public Channel parent() { return parent; }
        public CloseFuture closeFuture() { return closeFuture; }
    }
    public interface ContextView { Channel channel(); }
    private static final class HiddenContext implements ContextView { private final Channel channel; HiddenContext(Channel channel) { this.channel=channel; } public Channel channel() { return channel; } }
    public static final class Session {
        private final String key; Session(String key) { this.key=key; }
        public String getSessionKey() { return key; } public ContextView getInboundCtx() { return new HiddenContext(new Channel("stream-" + getRawStreamId(), parent)); }
        private static Channel parent;
        public String getRawStreamId() { return key.substring(key.lastIndexOf(':')+1); } public String getTargetHost() { return "example.test"; }
        public int getTargetPort() { return 443; }
    }
    @Test void tracksBoundedConnectionSessionAndCumulativeTrafficWithoutPayloads() throws Exception {
        List<TelemetryEvent> events=new CopyOnWriteArrayList<>();
        try (ProxyRemoteMonitor monitor=new ProxyRemoteMonitor("session",100,1,1,event->{events.add(event);return true;})) {
            Channel physical=new Channel("parent",null); Session.parent=physical;
            monitor.connectionOpened(physical,"http2");
            Session session=new Session("parent:7"); monitor.sessionRegistered(session); monitor.sessionActive(session);
            monitor.uplink(session,123); monitor.downlink(session,456);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(events.stream().noneMatch(event->event.kind()==TelemetryKind.TRAFFIC_SAMPLE)&&System.nanoTime()<deadline)Thread.sleep(10);
            TelemetryEvent sample=events.stream().filter(event->event.kind()==TelemetryKind.TRAFFIC_SAMPLE).findFirst().orElseThrow();
            assertEquals("parent",sample.connectionId()); assertEquals("7",sample.streamId());
            assertEquals("example.test",sample.targetHost()); assertEquals(123,sample.uplinkBytes()); assertEquals(456,sample.downlinkBytes());
            assertEquals(2,monitor.activeSnapshot().size());
            assertEquals("parent",monitor.activeSnapshot().get(0).connectionId());
            monitor.sessionClosed(session); physical.closeFuture.fire();
            assertTrue(events.stream().anyMatch(event->event.kind()==TelemetryKind.SESSION_CLOSED));
            assertTrue(events.stream().anyMatch(event->event.kind()==TelemetryKind.CLIENT_DISCONNECTED));
            assertEquals(0,monitor.sessionCount()); assertEquals(0,monitor.connectionCount());
        } finally { Session.parent=null; }
    }
    @Test void rejectsSessionsAtConfiguredCapacityAndReportsDrop() {
        List<TelemetryEvent> events=new CopyOnWriteArrayList<>();
        try (ProxyRemoteMonitor monitor=new ProxyRemoteMonitor("session",60000,1,1,event->{events.add(event);return true;})) {
            Channel parent=new Channel("parent",null); Session.parent=parent;
            java.util.stream.IntStream.range(0,100).parallel().forEach(i -> monitor.sessionRegistered(new Session("parent:"+i)));
            assertEquals(1,monitor.sessionCount()); assertEquals(99,monitor.dropped());
        } finally { Session.parent=null; }
    }
}