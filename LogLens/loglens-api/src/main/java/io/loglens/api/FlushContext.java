package io.loglens.api;

public record FlushContext(long monotonicNanos, long wallTimeMillis, String reason, boolean all) {}
