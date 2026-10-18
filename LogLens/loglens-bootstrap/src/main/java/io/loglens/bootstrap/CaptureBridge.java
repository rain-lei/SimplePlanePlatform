package io.loglens.bootstrap;

import java.util.Set;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Bootstrap-visible, JDK-only boundary. Never keep event references beyond enter(). */
public final class CaptureBridge {
    public interface Callback {
        /** Synchronously snapshot and enqueue. Return true only when accepted and takeover healthy. */
        boolean capture(Object logger, Object event);
        boolean healthy();
        boolean compatibleConsole(Object appender);
    }
    public interface TelemetryCallback {
        void connectionOpened(Object channel, String transport);
        void sessionRegistered(Object session);
        void sessionActive(Object session);
        void sessionClosed(Object session);
        void uplink(Object session, int byteCount);
        void downlink(Object session, int byteCount);
    }
    private record Scope(boolean takeover, Set<String> names) {}
    private static volatile Callback callback;
    private static volatile TelemetryCallback telemetry;
    private static volatile Set<String> consoleNames = Set.of();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private CaptureBridge() {}
    public static void install(Callback value, Set<String> names) {
        consoleNames = Set.copyOf(names);
        callback = value;
    }
    public static void installTelemetry(TelemetryCallback value) { telemetry = value; }
    public static void disableTelemetry() { telemetry = null; }
    public static void connectionOpened(Object channel, String transport) {
        TelemetryCallback target = telemetry; if (target != null) try { target.connectionOpened(channel, transport); } catch (Throwable ignored) { }
    }
    public static void sessionRegistered(Object session) {
        TelemetryCallback target = telemetry; if (target != null) try { target.sessionRegistered(session); } catch (Throwable ignored) { }
    }
    public static void sessionActive(Object session) {
        TelemetryCallback target = telemetry; if (target != null) try { target.sessionActive(session); } catch (Throwable ignored) { }
    }
    public static void sessionClosed(Object session) {
        TelemetryCallback target = telemetry; if (target != null) try { target.sessionClosed(session); } catch (Throwable ignored) { }
    }
    public static void uplink(Object session, int byteCount) {
        TelemetryCallback target = telemetry;
        if (target != null && byteCount > 0 && accepted(session, true)) try { target.uplink(session, byteCount); } catch (Throwable ignored) { }
    }
    public static void downlink(Object session, int byteCount) {
        TelemetryCallback target = telemetry;
        if (target != null && byteCount > 0 && accepted(session, false)) try { target.downlink(session, byteCount); } catch (Throwable ignored) { }
    }
    private static boolean accepted(Object session, boolean uplink) {
        try {
            Object state = invokePublic(session, "getState");
            if (uplink) {
                if (!"ACTIVE".equals(String.valueOf(state))) return false;
                Object channel = invokePublic(session, "getOutboundChannel");
                return channel != null && Boolean.TRUE.equals(invokePublic(channel, "isActive"));
            }
            if ("CLOSED".equals(String.valueOf(state))) return false;
            Object context = invokePublic(session, "getInboundCtx");
            Object channel = invokePublic(context, "channel");
            return Boolean.TRUE.equals(invokePublic(channel, "isActive"));
        } catch (Throwable ignored) { return false; }
    }
    private static Object invokePublic(Object target, String name) throws Exception {
        if (target == null) return null;
        Method method = publicMethod(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }
    private static Method publicMethod(Class<?> type, String name) {
        if (type == null) return null;
        if (Modifier.isPublic(type.getModifiers())) {
            try { return type.getMethod(name); } catch (NoSuchMethodException ignored) { }
        }
        for (Class<?> contract : type.getInterfaces()) {
            Method method = publicMethod(contract, name);
            if (method != null) return method;
        }
        return publicMethod(type.getSuperclass(), name);
    }
    public static void disable() { callback = null; telemetry = null; }
    /** Returns true when this invocation owns the scope, so advice can clean it in finally. */
    public static boolean enter(Object logger, Object event) {
        if (SCOPE.get() != null) return false;
        SCOPE.set(new Scope(false, Set.of()));
        Callback target = callback;
        if (target != null) {
            try {
                boolean accepted = target.capture(logger, event);
                SCOPE.set(new Scope(accepted, consoleNames));
            } catch (Throwable ignored) { /* Instrumentation must not break business logging. */ }
        }
        return true;
    }
    public static boolean skipConsole(Object appender, String name) {
        Scope scope = SCOPE.get();
        Callback target = callback;
        if (scope == null || !scope.takeover() || target == null || !scope.names().contains(name)) return false;
        try { return target.healthy() && target.compatibleConsole(appender); }
        catch (Throwable ignored) { return false; }
    }
    public static void exit(boolean owner) { if (owner) SCOPE.remove(); }
}
