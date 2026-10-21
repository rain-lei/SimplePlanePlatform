package io.loglens.adapter.logback;

import io.loglens.api.*;
import io.loglens.config.LogLensConfig;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflection is intentionally uncached: no application ClassLoader is retained by the adapter. */
public final class EventSnapshotter {
    private final LogLensConfig.Capture limits;
    private final LogClock clock;
    public EventSnapshotter(LogLensConfig.Capture limits, LogClock clock) { this.limits = limits; this.clock = clock; }
    private static final class Budget { boolean truncated; int frames; int exceptionChars; int nodes; }
    public CapturedLog snapshot(Object event) throws ReflectiveOperationException {
        String logger = String.valueOf(call(event, "getLoggerName"));
        LogLevel level = LogLevel.valueOf(String.valueOf(call(event, "getLevel")));
        if (!limits.accepts(logger, level)) return null;
        Budget budget = new Budget();
        String message = bounded(call(event, "getFormattedMessage"), limits.maxMessageChars(), budget);
        String template = bounded(call(event, "getMessage"), limits.maxMessageChars(), budget);
        List<String> parameters = new ArrayList<>();
        Object arguments = call(event, "getArgumentArray");
        if (arguments != null) {
            int count = Array.getLength(arguments); budget.truncated |= count > limits.maxParameters();
            for (int i = 0; i < Math.min(count, limits.maxParameters()); i++) parameters.add(bounded(Array.get(arguments, i), limits.maxParameterChars(), budget));
        }
        Map<String, String> mdc = new LinkedHashMap<>();
        Object rawMdc = call(event, "getMDCPropertyMap");
        if (rawMdc instanceof Map<?, ?> values) {
            for (var entry : values.entrySet()) {
                if (mdc.size() == limits.maxMdcEntries()) { budget.truncated = true; break; }
                mdc.put(bounded(entry.getKey(), 256, budget), bounded(entry.getValue(), limits.maxMdcValueChars(), budget));
            }
        }
        ExceptionSnapshot exception = exception(call(event, "getThrowableProxy"), 0, budget, new IdentityHashMap<>());
        List<String> caller = new ArrayList<>();
        if (limits.captureCaller()) {
            Object frames = call(event, "getCallerData");
            if (frames != null) {
                int count = Array.getLength(frames); budget.truncated |= count > limits.maxStackFrames();
                for (int i = 0; i < Math.min(count, limits.maxStackFrames()); i++) caller.add(bounded(Array.get(frames, i), 2048, budget));
            }
        }
        return new CapturedLog("pending", "pending", 0, ((Number) call(event, "getTimeStamp")).longValue(),
                clock.wallTimeMillis(), clock.monotonicNanos(), level, bounded(logger, 1024, budget),
                bounded(call(event, "getThreadName"), 1024, budget), template, message, parameters, mdc,
                exception, caller, budget.truncated);
    }
    private ExceptionSnapshot exception(Object proxy, int depth, Budget budget, IdentityHashMap<Object, Boolean> seen) throws ReflectiveOperationException {
        if (proxy == null) return null;
        if (depth >= 16 || budget.nodes >= 64 || seen.put(proxy, Boolean.TRUE) != null) {
            budget.truncated = true;
            return new ExceptionSnapshot("[truncated]", "exception graph limit", List.of(), List.of(), null, true);
        }
        budget.nodes++;
        String type = bounded(call(proxy, "getClassName"), 512, budget);
        int remaining = Math.max(0, limits.maxExceptionChars() - budget.exceptionChars);
        String message = bounded(call(proxy, "getMessage"), remaining, budget); budget.exceptionChars += message.length();
        List<String> frames = new ArrayList<>();
        Object rawFrames = call(proxy, "getStackTraceElementProxyArray");
        if (rawFrames != null) {
            int count = Array.getLength(rawFrames);
            int allowed = Math.min(count, Math.max(0, limits.maxStackFrames() - budget.frames));
            budget.truncated |= allowed < count;
            for (int i = 0; i < allowed; i++) {
                Object frame = Array.get(rawFrames, i);
                frames.add(bounded(call(frame, "getStackTraceElement"), 2048, budget));
            }
            budget.frames += allowed;
        }
        List<ExceptionSnapshot> suppressed = new ArrayList<>();
        Object rawSuppressed = call(proxy, "getSuppressed");
        if (rawSuppressed != null) {
            int count = Array.getLength(rawSuppressed); budget.truncated |= count > 8;
            for (int i = 0; i < Math.min(count, 8); i++) suppressed.add(exception(Array.get(rawSuppressed, i), depth + 1, budget, seen));
        }
        ExceptionSnapshot cause = exception(call(proxy, "getCause"), depth + 1, budget, seen);
        return new ExceptionSnapshot(type, message, frames, suppressed, cause, budget.truncated);
    }
    private static Object call(Object target, String method) throws ReflectiveOperationException { return target.getClass().getMethod(method).invoke(target); }
    private static String bounded(Object value, int maximum, Budget budget) {
        String text;
        try { text = value == null ? "" : String.valueOf(value); }
        catch (Throwable failure) { text = "[string conversion failed]"; budget.truncated = true; }
        if (text.length() > maximum) { budget.truncated = true; return text.substring(0, maximum); }
        return text;
    }
}
