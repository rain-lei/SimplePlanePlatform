package com.example;

import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;
import org.slf4j.MDC;

/** Small deterministic integration fixture; no LogLens dependency or business-code changes. */
public final class LogbackDemo {
    public static void main(String[] args) {
        var logger = LoggerFactory.getLogger(LogbackDemo.class);
        MDC.put("traceId", "trace-demo");
        MDC.put("token", "private");
        try {
            logger.debug("disabled-debug-probe");
            logger.info("orderId={} token=private", "O-1");
            logger.info("orderId={} token=private", "O-2");
            logger.error("payment failed", new IllegalStateException("demo failure"));
            if (Boolean.getBoolean("demo.turbo")) logger.info(MarkerFactory.getMarker("BLOCKED"), "turbo-filtered-probe");
            if (Boolean.getBoolean("demo.reset")) {
                var context = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
                context.reset();
                var configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
                configurator.setContext(context);
                try { configurator.doConfigure(System.getProperty("logback.configurationFile")); }
                catch (ch.qos.logback.core.joran.spi.JoranException failure) { throw new IllegalStateException(failure); }
                logger.warn("after-reset-probe");
            }
        } finally {
            MDC.clear();
            if (Boolean.getBoolean("demo.async")) {
                try { Thread.sleep(250); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
