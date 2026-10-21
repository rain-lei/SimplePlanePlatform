package io.loglens.adapter.logback;

import io.loglens.api.CapturedLog;
import io.loglens.api.LogClock;
import io.loglens.bootstrap.CaptureBridge;
import io.loglens.config.LogLensConfig;
import io.loglens.runtime.ProcessingRuntime;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.Set;

/** Callback is invoked synchronously by the bootstrap bridge on the business thread. */
public final class LogbackCapture implements CaptureBridge.Callback {
    private final ProcessingRuntime runtime;
    private final EventSnapshotter snapshotter;
    public LogbackCapture(ProcessingRuntime runtime, LogClock clock) {
        this.runtime = runtime; snapshotter = new EventSnapshotter(runtime.config().capture(), clock);
    }
    @Override public boolean capture(Object logger, Object event) {
        if (!runtime.healthy()) return false;
        try {
            CapturedLog snapshot = snapshotter.snapshot(event);
            if (snapshot == null) return false;
            boolean replace = runtime.config().agent().mode() == LogLensConfig.Mode.REPLACE_CONSOLE;
            if (replace && !supportedAppenders(logger)) replace = false;
            return runtime.publish(snapshot) && replace;
        } catch (Throwable failure) {
            runtime.storage().report("CAPTURE_FAILED", failure.getClass().getName());
            return false;
        }
    }
    @Override public boolean healthy() { return runtime.healthy(); }
    @Override public boolean compatibleConsole(Object appender) { return isConsole(appender); }
    private boolean supportedAppenders(Object logger) throws ReflectiveOperationException {
        Object context = logger.getClass().getMethod("getLoggerContext").invoke(logger);
        // Inspect the effective logger ancestry without retaining any framework instance.
        String name = String.valueOf(logger.getClass().getMethod("getName").invoke(logger));
        Object current = logger;
        int ancestry = 0;
        while (current != null && ancestry++ < 128) {
            Object iterator = current.getClass().getMethod("iteratorForAppenders").invoke(current);
            if (iterator instanceof Iterator<?> appenders) {
                int count = 0;
                while (appenders.hasNext()) {
                    if (++count > 1024 || !supported(appenders.next(), false, new IdentityHashMap<>(), 0)) {
                        runtime.storage().report("UNSUPPORTED_CONSOLE_WRAPPER", "Observe fallback for configured console wrapper");
                        return false;
                    }
                }
            }
            if (name.equals("ROOT") || !Boolean.TRUE.equals(current.getClass().getMethod("isAdditive").invoke(current))) break;
            int dot = name.lastIndexOf('.'); name = dot < 0 ? "ROOT" : name.substring(0, dot);
            current = context.getClass().getMethod("getLogger", String.class).invoke(context, name);
        }
        return ancestry < 128;
    }
    private boolean supported(Object appender, boolean wrapped, IdentityHashMap<Object, Boolean> seen, int depth) throws ReflectiveOperationException {
        if (depth >= 32 || seen.put(appender, Boolean.TRUE) != null) return false;
        Set<String> targets = runtime.config().agent().consoleAppenderNames();
        String name = String.valueOf(appender.getClass().getMethod("getName").invoke(appender));
        if (targets.contains(name) && (wrapped || !isConsole(appender))) return false;
        try {
            Object children = appender.getClass().getMethod("iteratorForAppenders").invoke(appender);
            if (children instanceof Iterator<?> iterator) {
                int count = 0;
                while (iterator.hasNext()) if (++count > 1024 || !supported(iterator.next(), true, seen, depth + 1)) return false;
            }
        } catch (NoSuchMethodException leaf) { /* A direct appender has no children. */ }
        return true;
    }
    private static boolean isConsole(Object appender) {
        if (appender == null) return false;
        for (Class<?> type = appender.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("ch.qos.logback.core.ConsoleAppender")) return true;
        return false;
    }
}
