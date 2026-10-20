package com.proxy.crypto;

/** AES-256-GCM with authenticated record headers and per-stream derived keys. */
public final class AesGcmV2Cipher extends AeadRecordCipher {
    public AesGcmV2Cipher() {
        super(2);
    }
}
