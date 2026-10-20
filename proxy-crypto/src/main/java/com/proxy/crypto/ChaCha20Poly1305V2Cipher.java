package com.proxy.crypto;

/** RFC 8439 AEAD in the versioned record format; distinct from legacy chacha20. */
public final class ChaCha20Poly1305V2Cipher extends AeadRecordCipher {
    public ChaCha20Poly1305V2Cipher() {
        super(1);
    }
}
