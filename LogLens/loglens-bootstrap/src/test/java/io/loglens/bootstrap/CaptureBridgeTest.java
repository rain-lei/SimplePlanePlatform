package io.loglens.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class CaptureBridgeTest {
    @AfterEach void reset() { CaptureBridge.disable(); CaptureBridge.exit(true); }
    @Test void rejectedCaptureNeverSuppressesOriginalConsole() {
        CaptureBridge.install(callback(false), Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try { assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE")); }
        finally { CaptureBridge.exit(owner); }
    }
    @Test void successfulCaptureSuppressesOnlyNamedCompatibleConsoleAndDisableReverts() {
        CaptureBridge.install(callback(true), Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try {
            assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
            assertFalse(CaptureBridge.skipConsole(new Object(), "OTHER"));
            CaptureBridge.disable();
            assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        } finally { CaptureBridge.exit(owner); }
    }
    @Test void nestedInstrumentationDoesNotRecaptureAndDoesNotRemoveOuterScope() {
        AtomicInteger captures = new AtomicInteger();
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) {
                captures.incrementAndGet();
                boolean nestedOwner = CaptureBridge.enter(logger, event);
                assertFalse(nestedOwner);
                CaptureBridge.exit(nestedOwner);
                return true;
            }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(new Object(), new Object());
        try { assertEquals(1, captures.get()); assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE")); }
        finally { CaptureBridge.exit(owner); }
        assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
    }
    @Test void captureExceptionsAreIsolatedAndNextEventCanCapture() {
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { throw new IllegalStateException("plugin"); }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        boolean owner = CaptureBridge.enter(null, null);
        assertTrue(owner);
        assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        CaptureBridge.exit(owner);
        CaptureBridge.install(callback(true), Set.of("CONSOLE"));
        owner = CaptureBridge.enter(null, null);
        assertTrue(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        CaptureBridge.exit(owner);
    }
    public static final class ChannelFixture { private final boolean active; ChannelFixture(boolean active) { this.active=active; } public boolean isActive() { return active; } }
    public static final class ContextFixture { private final ChannelFixture channel; ContextFixture(boolean active) { channel=new ChannelFixture(active); } public ChannelFixture channel() { return channel; } }
    public static final class SessionFixture {
        private final String state; private final ChannelFixture outbound; private final ContextFixture inbound;
        SessionFixture(String state, boolean outbound, boolean inbound) { this.state=state; this.outbound=new ChannelFixture(outbound); this.inbound=new ContextFixture(inbound); }
        public String getState() { return state; } public ChannelFixture getOutboundChannel() { return outbound; } public ContextFixture getInboundCtx() { return inbound; }
    }
    public interface HiddenSessionAccess { String getState(); ChannelFixture getOutboundChannel(); HiddenContextAccess getInboundCtx(); }
    public interface HiddenContextAccess { ChannelFixture channel(); }
    private static final class HiddenContext implements HiddenContextAccess {
        private final ChannelFixture channel = new ChannelFixture(true);
        public ChannelFixture channel() { return channel; }
    }
    private static final class HiddenSession implements HiddenSessionAccess {
        public String getState() { return "ACTIVE"; }
        public ChannelFixture getOutboundChannel() { return new ChannelFixture(true); }
        public HiddenContextAccess getInboundCtx() { return new HiddenContext(); }
    }
    @Test void byteCountersOnlyRunForSuccessfulForwardingStates() {
        AtomicInteger uplink=new AtomicInteger(), downlink=new AtomicInteger();
        CaptureBridge.installTelemetry(new CaptureBridge.TelemetryCallback() {
            public void connectionOpened(Object channel,String transport) { }
            public void sessionRegistered(Object session) { }
            public void sessionActive(Object session) { }
            public void sessionClosed(Object session) { }
            public void uplink(Object session,int bytes) { uplink.addAndGet(bytes); }
            public void downlink(Object session,int bytes) { downlink.addAndGet(bytes); }
        });
        SessionFixture active=new SessionFixture("ACTIVE",true,true);
        CaptureBridge.uplink(active,12); CaptureBridge.downlink(active,7);
        CaptureBridge.uplink(new SessionFixture("CONNECTING",true,true),9);
        CaptureBridge.uplink(new SessionFixture("ACTIVE",false,true),8);
        CaptureBridge.downlink(new SessionFixture("CLOSED",true,true),5);
        CaptureBridge.downlink(new SessionFixture("ACTIVE",true,false),4);
        CaptureBridge.uplink(new HiddenSession(), 3); CaptureBridge.downlink(new HiddenSession(), 5);
        assertEquals(15,uplink.get()); assertEquals(12,downlink.get());
    }
    private CaptureBridge.Callback callback(boolean accepted) {
        return new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { return accepted; }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        };
    }
}
