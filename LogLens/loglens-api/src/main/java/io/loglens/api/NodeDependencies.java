package io.loglens.api;

public record NodeDependencies(LogClock clock, DiagnosticSink diagnostics, SourceIndex sourceIndex) {
    public NodeDependencies(LogClock clock, DiagnosticSink diagnostics) {
        this(clock, diagnostics, (sequence, groupBy, groupValues) -> {});
    }
}
