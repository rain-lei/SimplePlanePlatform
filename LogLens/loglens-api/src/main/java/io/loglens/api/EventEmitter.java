package io.loglens.api;

@FunctionalInterface
public interface EventEmitter { void emit(LogContext context); }
