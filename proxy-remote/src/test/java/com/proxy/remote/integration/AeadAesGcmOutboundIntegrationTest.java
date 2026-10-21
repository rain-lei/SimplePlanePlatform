package com.proxy.remote.integration;

/** Reuses the real TCP outbound/echo/session suite with authenticated AES-GCM records. */
class AeadAesGcmOutboundIntegrationTest extends OutboundIntegrationTest {
    @Override String cipherSuite() { return "aes-gcm-v2"; }
}
