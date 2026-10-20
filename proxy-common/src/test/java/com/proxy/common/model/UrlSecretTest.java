package com.proxy.common.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlSecretTest {
    @Test
    void loggingDoesNotExposeCipherKeyOrMutateConfiguration() {
        URL url = new URL("proxy", "localhost", 9090).addParameter("cipherKey", "sensitive-key")
                .addParameter("cipher", "aes-gcm-v2");
        assertFalse(url.toString().contains("sensitive-key"));
        assertTrue(url.toString().contains("cipherKey=[REDACTED]"));
        assertEquals("sensitive-key", url.getParameter("cipherKey"));
    }
}
