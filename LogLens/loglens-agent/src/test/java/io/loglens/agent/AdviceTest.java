package io.loglens.agent;

import io.loglens.bootstrap.CaptureBridge;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.*;

class AdviceTest {
    public static class Dispatch {
        public void callAppenders(Object event) { if (event == null) throw new IllegalStateException(); }
    }
    public static class Appender {
        protected String name = "CONSOLE";
        public int calls;
        public void doAppend(Object event) { calls++; }
    }
    @Test void inlinedDispatchAdviceCapturesOnceAndAlwaysClearsScope() throws Exception {
        AtomicInteger captured = new AtomicInteger();
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { captured.incrementAndGet(); return true; }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        try {
            Class<?> type = new ByteBuddy().redefine(Dispatch.class).visit(Advice.to(DispatchAdvice.class).on(named("callAppenders")))
                    .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
            Object instance = type.getConstructor().newInstance();
            type.getMethod("callAppenders", Object.class).invoke(instance, new Object());
            assertEquals(1, captured.get());
            assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> type.getMethod("callAppenders", Object.class).invoke(instance, new Object[]{null}));
            assertEquals(2, captured.get());
            assertFalse(CaptureBridge.skipConsole(new Object(), "CONSOLE"));
        } finally { CaptureBridge.disable(); CaptureBridge.exit(true); }
    }
    @Test void consoleAdviceSkipsOnlyAcceptedCaptureScope() throws Exception {
        CaptureBridge.install(new CaptureBridge.Callback() {
            public boolean capture(Object logger, Object event) { return true; }
            public boolean healthy() { return true; }
            public boolean compatibleConsole(Object appender) { return true; }
        }, Set.of("CONSOLE"));
        try {
            Class<?> type = new ByteBuddy().redefine(Appender.class).visit(Advice.to(ConsoleAdvice.class).on(named("doAppend")))
                    .make().load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
            Object instance = type.getConstructor().newInstance();
            type.getMethod("doAppend", Object.class).invoke(instance, new Object());
            assertEquals(1, type.getField("calls").getInt(instance));
            boolean owner = CaptureBridge.enter(new Object(), new Object());
            try { type.getMethod("doAppend", Object.class).invoke(instance, new Object()); }
            finally { CaptureBridge.exit(owner); }
            assertEquals(1, type.getField("calls").getInt(instance));
        } finally { CaptureBridge.disable(); CaptureBridge.exit(true); }
    }
}
