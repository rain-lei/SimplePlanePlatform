package io.loglens.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, already redacted snapshot; wall-clock timestamps use epoch milliseconds. */
public record CapturedLog(String eventId, String sessionId, long captureSequence,
                          long eventTime, long capturedAt, long monotonicNanos,
                          LogLevel level, String logger, String thread, String template,
                          String message, List<String> parameters, Map<String, String> mdc,
                          ExceptionSnapshot exception, List<String> caller, boolean truncated) {
    public CapturedLog {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(level);
        logger = logger == null ? "" : logger;
        thread = thread == null ? "" : thread;
        template = template == null ? "" : template;
        message = message == null ? "" : message;
        parameters = List.copyOf(parameters);
        mdc = Map.copyOf(mdc);
        caller = List.copyOf(caller);
    }
    public String exceptionType() { return exception == null ? "" : exception.type(); }
}
