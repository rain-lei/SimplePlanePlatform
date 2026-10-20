package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import com.proxy.common.spi.ExtensionLoader;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.proxy.crypto.AeadStandardVectorTest.hex;

class AeadRecordCipherTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final String[] SUITES = {"chacha20-poly1305-v2", "aes-gcm-v2"};

    private Cipher cipher(String name, boolean server, byte[] key) {
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).newExtension(name);
        CipherConfig config = new CipherConfig(key);
        config.setServerSide(server);
        cipher.init(config);
        return cipher;
    }

    private static void set(Cipher cipher, String name, Object value) throws Exception {
        Field field = AeadRecordCipher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(cipher, value);
    }

    @Test
    void independentReferenceVectorsEncryptAndDecryptExactly() throws Exception {
        Map<String, Cipher> senders = new HashMap<>();
        Map<String, Cipher> receivers = new HashMap<>();
        int count = 0;
        for (String line : Files.readAllLines(Paths.get("../docs/design/crypto-v2-vectors.tsv"), StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            int suite = Integer.parseInt(f[0]);
            boolean server = f[1].equals("1");
            String id = f[0] + "/" + f[1];
            if (!senders.containsKey(id)) {
                Cipher sender = cipher(SUITES[suite - 1], server, hex(f[3]));
                set(sender, "sendSalt", hex(f[4]));
                set(sender, "sendKey", AeadSupport.derive(hex(f[3]), hex(f[4]), suite, server ? 1 : 0));
                assertArrayEquals(hex(f[5]), AeadSupport.derive(hex(f[3]), hex(f[4]), suite, server ? 1 : 0));
                senders.put(id, sender);
                receivers.put(id, cipher(SUITES[suite - 1], !server, hex(f[3])));
            }
            assertArrayEquals(hex(f[7]), senders.get(id).encrypt(hex(f[6])));
            assertArrayEquals(hex(f[6]), receivers.get(id).decrypt(hex(f[7])));
            count++;
        }
        assertEquals(40, count);
    }

    @Test
    void everyRecordByteIsAuthenticatedAndFailureDoesNotAdvanceState() {
        for (String name : SUITES) {
            Cipher sender = cipher(name, false, KEY);
            Cipher receiver = cipher(name, true, KEY);
            byte[] record = sender.encrypt(new byte[80]);
            for (int i = 0; i < record.length; i++) {
                byte[] changed = record.clone();
                changed[i] ^= 1;
                assertThrows(CryptoException.class, () -> receiver.decrypt(changed), name + " byte " + i);
            }
            for (int length = 0; length < record.length; length++) {
                byte[] shortRecord = Arrays.copyOf(record, length);
                assertThrows(CryptoException.class, () -> receiver.decrypt(shortRecord));
            }
            assertArrayEquals(new byte[80], receiver.decrypt(record));
            assertThrows(CryptoException.class, () -> receiver.decrypt(record));
        }
    }

    @Test
    void rejectReflectionWrongKeysMixedStreamsAndDowngrade() {
        for (String name : SUITES) {
            Cipher sender = cipher(name, false, KEY);
            byte[] a = sender.encrypt(new byte[]{1});
            byte[] b = sender.encrypt(new byte[]{2});
            Cipher receiver = cipher(name, true, KEY);
            assertThrows(CryptoException.class, () -> receiver.decrypt(b));
            assertThrows(CryptoException.class, () -> sender.decrypt(a));
            byte[] wrongKey = KEY.clone(); wrongKey[0] ^= 1;
            assertThrows(CryptoException.class, () -> cipher(name, true, wrongKey).decrypt(a));
            assertArrayEquals(new byte[]{1}, receiver.decrypt(a));
            assertThrows(CryptoException.class, () -> receiver.decrypt(cipher(name, false, KEY).encrypt(new byte[]{9})));
            assertArrayEquals(new byte[]{2}, receiver.decrypt(b));
            Cipher legacy = new ChaCha20Cipher(); legacy.init(new CipherConfig(KEY));
            assertThrows(CryptoException.class, () -> cipher(name, true, KEY).decrypt(legacy.encrypt(new byte[]{1})));
            assertThrows(CryptoException.class, () -> receiver.init(new CipherConfig(KEY)));
            assertThrows(CryptoException.class, () -> cipher(name, false, new byte[31]));
        }
    }

    @Test
    void boundaryLengthsAndBothDirections() {
        for (String name : SUITES) {
            Cipher client = cipher(name, false, KEY);
            Cipher server = cipher(name, true, KEY);
            for (int length : new int[]{0,1,15,16,17,31,32,33,63,64,65,16383,16384,16385,2*1024*1024}) {
                byte[] plain = new byte[length]; new Random(length).nextBytes(plain);
                assertArrayEquals(plain, server.decrypt(client.encrypt(plain)));
                assertArrayEquals(plain, client.decrypt(server.encrypt(plain)));
            }
            assertThrows(CryptoException.class, () -> client.encrypt(null));
            assertThrows(CryptoException.class, () -> client.encrypt(new byte[AeadRecordCipher.MAX_PLAINTEXT + 1]));
        }
    }

    @Test
    void instanceIsolationAndConcurrentSequences() throws Exception {
        Cipher sender = cipher(SUITES[0], false, KEY);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < 256; i++) results.add(pool.submit(() -> sender.encrypt(new byte[]{42})));
            TreeMap<Long, byte[]> ordered = new TreeMap<>();
            for (Future<byte[]> f : results) {
                byte[] record = f.get(10, TimeUnit.SECONDS);
                assertNull(ordered.put(ByteBuffer.wrap(record, 38, 8).getLong(), record));
            }
            Cipher receiver = cipher(SUITES[0], true, KEY);
            for (byte[] record : ordered.values()) assertArrayEquals(new byte[]{42}, receiver.decrypt(record));
            assertEquals(256, ordered.size());
            byte[] different = cipher(SUITES[0], false, KEY).encrypt(new byte[]{42});
            assertFalse(Arrays.equals(Arrays.copyOfRange(different, 6, 38), Arrays.copyOfRange(ordered.firstEntry().getValue(), 6, 38)));
            // A second configured key cannot mutate the already initialized sender.
            byte[] anotherKey = KEY.clone(); anotherKey[0] ^= 1;
            cipher(SUITES[0], false, anotherKey);
            assertArrayEquals(new byte[]{7}, receiver.decrypt(sender.encrypt(new byte[]{7})));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void countersAndByteLimitsFailClosed() throws Exception {
        for (String name : SUITES) {
            Cipher sender = cipher(name, false, KEY);
            set(sender, "sendSequence", AeadRecordCipher.MAX_RECORDS);
            assertThrows(CryptoException.class, () -> sender.encrypt(new byte[0]));
            Cipher finalSender = cipher(name, false, KEY);
            set(finalSender, "sentBytes", AeadRecordCipher.MAX_BYTES);
            assertThrows(CryptoException.class, () -> finalSender.encrypt(new byte[1]));
        }
    }
}
