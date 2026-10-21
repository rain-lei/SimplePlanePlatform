package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Fixed-seed micro workload for repeatable baseline/Agent comparisons. */
public final class PerformanceDemo {
    private static final long SEED = 20261012L;
    private PerformanceDemo() {}
    public static void main(String[] args) {
        int count = Integer.getInteger("demo.perf.count", 100_000);
        if (count < 1 || count > 1_000_000) throw new IllegalArgumentException("demo.perf.count must be 1..1000000");
        Logger[] loggers = new Logger[64];
        for (int i = 0; i < loggers.length; i++) loggers[i] = LoggerFactory.getLogger("com.example.perf.bucket" + i);
        long[] latency = new long[count];
        SplittableRandom random = new SplittableRandom(SEED);
        var operatingSystem = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long gcCountBefore = gcCount(), gcMillisBefore = gcMillis();
        long cpuBefore = operatingSystem.getProcessCpuTime();
        long peakHeap = memory.getHeapMemoryUsage().getUsed();
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long callStarted = System.nanoTime();
            loggers[random.nextInt(loggers.length)].info("benchmark payload={}", random.nextLong());
            latency[i] = System.nanoTime() - callStarted;
            if ((i & 1023) == 0) peakHeap = Math.max(peakHeap, memory.getHeapMemoryUsage().getUsed());
        }
        long elapsed = System.nanoTime() - started;
        long cpu = Math.max(0, operatingSystem.getProcessCpuTime() - cpuBefore);
        peakHeap = Math.max(peakHeap, memory.getHeapMemoryUsage().getUsed());
        Arrays.sort(latency);
        System.out.printf("PERF_RESULT seed=%d count=%d elapsedNanos=%d throughputPerSecond=%.2f p50Nanos=%d p95Nanos=%d p99Nanos=%d maxNanos=%d processCpuNanos=%d gcCount=%d gcMillis=%d sampledPeakHeapBytes=%d%n",
                SEED, count, elapsed, count * 1_000_000_000.0 / elapsed,
                percentile(latency, .50), percentile(latency, .95), percentile(latency, .99), latency[count - 1], cpu,
                gcCount() - gcCountBefore, gcMillis() - gcMillisBefore, peakHeap);
    }
    private static long percentile(long[] sorted, double percentile) { return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1)]; }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(value -> value >= 0).sum(); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(value -> value >= 0).sum(); }
}
