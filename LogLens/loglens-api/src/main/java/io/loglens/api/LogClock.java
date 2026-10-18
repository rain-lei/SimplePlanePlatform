package io.loglens.api;

/** Split clocks: duration uses monotonic time, presentation uses wall time. */
public interface LogClock {
    long wallTimeMillis();
    long monotonicNanos();
    static LogClock system() {
        return new LogClock() {
            public long wallTimeMillis() { return System.currentTimeMillis(); }
            public long monotonicNanos() { return System.nanoTime(); }
        };
    }
}
