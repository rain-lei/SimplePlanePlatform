package io.loglens.api;

/** All callbacks are synchronous on the single consumer; no unbounded or network work. */
public interface LogProcessor extends AutoCloseable {
    String nodeId();
    default void beforeProcess(FlushContext flush, LogContext input, EventEmitter downstream) {}
    void process(LogContext context, ChainContinuation next);
    default void flush(FlushContext context, EventEmitter downstream) {}
    @Override default void close() {}
}
