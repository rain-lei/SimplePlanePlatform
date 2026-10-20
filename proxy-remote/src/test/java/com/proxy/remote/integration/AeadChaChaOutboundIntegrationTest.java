package com.proxy.remote.integration;

/** Reuses the real TCP outbound/echo/session suite with authenticated ChaCha records. */
class AeadChaChaOutboundIntegrationTest extends OutboundIntegrationTest {
    @Override String cipherSuite() { return "chacha20-poly1305-v2"; }
}
