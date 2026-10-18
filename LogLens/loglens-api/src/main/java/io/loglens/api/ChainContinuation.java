package io.loglens.api;

@FunctionalInterface
public interface ChainContinuation { void proceed(LogContext context); }
