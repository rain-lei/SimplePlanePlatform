package io.loglens.api;

import java.util.List;

/** Only JDK strings cross the application/consumer thread boundary. */
public record ExceptionSnapshot(String type, String message, List<String> frames,
                                List<ExceptionSnapshot> suppressed, ExceptionSnapshot cause,
                                boolean truncated) {
    public ExceptionSnapshot {
        type = type == null ? "" : type;
        message = message == null ? "" : message;
        frames = List.copyOf(frames);
        suppressed = List.copyOf(suppressed);
    }
}
