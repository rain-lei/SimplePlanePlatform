package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@SpringBootApplication
public class BootDemo {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(BootDemo.class, args)) { }
    }
    @Bean CommandLineRunner logs() {
        return args -> {
            var logger = LoggerFactory.getLogger(BootDemo.class);
            MDC.put("traceId", "boot-trace");
            try {
                logger.info("orderId={} token=private", "BOOT-1");
                logger.info("orderId={} token=private", "BOOT-2");
                logger.error("boot payment failed", new IllegalStateException("boot failure"));
            } finally { MDC.clear(); }
        };
    }
}
