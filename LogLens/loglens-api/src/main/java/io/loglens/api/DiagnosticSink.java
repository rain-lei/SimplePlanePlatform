package io.loglens.api;

/** Diagnostic messages must not contain raw application data. */
@FunctionalInterface
public interface DiagnosticSink { void report(String code, String message); }
