package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/** Versioned, direction-separated AEAD records behind the existing Cipher SPI. */
public abstract class AeadRecordCipher implements Cipher {
    static final int HEADER_LENGTH = 50;
    static final int TAG_LENGTH = 16;
    static final int MAX_PLAINTEXT = 8 * 1024 * 1024 + 1024;
    static final long MAX_RECORDS = 1L << 32;
    static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    private static final int MAGIC = 0x53504332; // SPC2
    private final int suite;
    private byte[] master;
    private byte[] sendSalt;
    private byte[] sendKey;
    private byte[] receiveSalt;
    private byte[] receiveKey;
    private int sendDirection;
    private long sendSequence;
    private long receiveSequence;
    private long sentBytes;
    private long receivedBytes;

    protected AeadRecordCipher(int suite) {
        this.suite = suite;
    }

    @Override
    public synchronized void init(CipherConfig config) {
        if (master != null) {
            throw new CryptoException("Cipher already initialized; create a new stream instance");
        }
        byte[] key = config == null ? null : config.getKey();
        if (key == null || key.length != 32) {
            throw new CryptoException("AEAD v2 requires an exact 32-byte high-entropy shared key");
        }
        master = key.clone();
        sendDirection = config.isServerSide() ? 1 : 0;
        sendSalt = new byte[32];
        new SecureRandom().nextBytes(sendSalt);
        sendKey = AeadSupport.derive(master, sendSalt, suite, sendDirection);
    }

    @Override
    public synchronized byte[] encrypt(byte[] plaintext) {
        requireInitialized();
        if (plaintext == null || plaintext.length > MAX_PLAINTEXT) {
            throw new CryptoException("Invalid AEAD plaintext length");
        }
        checkLimit(sendSequence, sentBytes, plaintext.length);
        long sequence = sendSequence++;
        sentBytes += plaintext.length; // Never roll a nonce back after an error.
        byte[] header = ByteBuffer.allocate(HEADER_LENGTH).putInt(MAGIC)
                .put((byte) suite).put((byte) sendDirection).put(sendSalt)
                .putLong(sequence).putInt(plaintext.length).array();
        byte[] body = AeadSupport.crypt(true, suite, sendKey, nonce(sequence), header, plaintext);
        byte[] record = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, record, header.length, body.length);
        return record;
    }

    @Override
    public synchronized byte[] decrypt(byte[] record) {
        requireInitialized();
        if (record == null || record.length < HEADER_LENGTH + TAG_LENGTH
                || record.length > HEADER_LENGTH + MAX_PLAINTEXT + TAG_LENGTH) {
            throw new CryptoException("Invalid AEAD record length");
        }
        ByteBuffer input = ByteBuffer.wrap(record);
        if (input.getInt() != MAGIC || input.get() != suite || input.get() != 1 - sendDirection) {
            throw new CryptoException("AEAD version, suite or direction mismatch (no fallback)");
        }
        byte[] salt = new byte[32];
        input.get(salt);
        long sequence = input.getLong();
        int length = input.getInt();
        if (length < 0 || length != record.length - HEADER_LENGTH - TAG_LENGTH
                || sequence != receiveSequence
                || (receiveSalt != null && !Arrays.equals(receiveSalt, salt))) {
            throw new CryptoException("Invalid AEAD length, stream identity or sequence");
        }
        checkLimit(sequence, receivedBytes, length);
        byte[] key = receiveKey == null ? AeadSupport.derive(master, salt, suite, 1 - sendDirection) : receiveKey;
        byte[] plaintext = AeadSupport.crypt(false, suite, key, nonce(sequence),
                Arrays.copyOf(record, HEADER_LENGTH), Arrays.copyOfRange(record, HEADER_LENGTH, record.length));
        // Publish receive state only after successful authentication.
        receiveSalt = salt;
        receiveKey = key;
        receiveSequence++;
        receivedBytes += length;
        return plaintext;
    }

    private void requireInitialized() {
        if (master == null) {
            throw new CryptoException("Cipher not initialized");
        }
    }

    private static void checkLimit(long sequence, long bytes, int length) {
        if (sequence < 0 || sequence >= MAX_RECORDS || bytes > MAX_BYTES - length) {
            throw new CryptoException("AEAD stream key limit reached; open a new stream");
        }
    }

    static byte[] nonce(long sequence) {
        return ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
    }
}
