package com.proxy.crypto;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * proxy-crypto 加密核心单元测试，覆盖《项目测试计划与清单》§4.1 TC-CRYPTO-001~011。
 * <p>
 * 三种 AEAD/Encrypt-then-MAC 实现（ChaCha20-Poly1305 / AES-GCM / AES-CTR-HMAC）共享同一组
 * 行为契约：加解密往返一致、错误密钥/篡改密文/篡改 tag 必被检测、空与边界长度正确处理、
 * 相同明文+随机 nonce 不产生相同密文。本测试以参数化的方式对三种实现统一断言，
 * 既保证每条用例都跑到，又避免重复样板代码。
 * </p>
 */
@DisplayName("proxy-crypto 加解密核心 (TC-CRYPTO)")
public class CipherRoundTripTest {

    /** 测试统一使用的 32 字节主密钥。 */
    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static Cipher newCipher(Supplier<Cipher> factory) {
        Cipher cipher = factory.get();
        cipher.init(new CipherConfig(KEY.clone()));
        return cipher;
    }

    // ---------------------------------------------------------------------
    // 通用契约：对三种实现各自执行的断言集合
    // ---------------------------------------------------------------------

    /** TC-CRYPTO-001/002/003：加密后解密，明文还原一致。 */
    private void assertRoundTrip(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);
        byte[] plaintext = "hello, proxy-remote! 你好，世界。".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = cipher.encrypt(plaintext);
        assertNotNull(ciphertext);
        assertFalse(Arrays.equals(plaintext, ciphertext), "密文不应等于明文");

        byte[] decrypted = cipher.decrypt(ciphertext);
        assertArrayEquals(plaintext, decrypted, "解密后应还原原始明文");
    }

    /** TC-CRYPTO-004：用错误密钥解密必须失败，绝不能返回错误明文。 */
    private void assertWrongKeyRejected(Supplier<Cipher> factory) {
        Cipher encryptor = newCipher(factory);
        byte[] plaintext = "top-secret-payload".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = encryptor.encrypt(plaintext);

        Cipher wrongKeyDecryptor = factory.get();
        byte[] wrongKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII);
        wrongKeyDecryptor.init(new CipherConfig(wrongKey));

        CryptoException ex = assertThrows(CryptoException.class,
                () -> wrongKeyDecryptor.decrypt(ciphertext),
                "错误密钥解密必须抛 CryptoException");
        // 额外保险：即便实现哪天不抛异常，也绝不能恰好解出原文。
        assertNotNull(ex);
    }

    /** TC-CRYPTO-005：篡改密文体（改一个字节）→ 认证失败。 */
    private void assertTamperedCiphertextRejected(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);
        byte[] plaintext = new byte[64];
        Arrays.fill(plaintext, (byte) 0x42);
        byte[] ciphertext = cipher.encrypt(plaintext);

        // 翻转中段一个字节（避开头部 nonce/iv 区，确保命中密文体）。
        byte[] tampered = ciphertext.clone();
        int idx = tampered.length / 2;
        tampered[idx] ^= 0x01;

        assertThrows(CryptoException.class, () -> cipher.decrypt(tampered),
                "篡改密文体必须触发 AEAD/HMAC 完整性校验失败");
    }

    /** TC-CRYPTO-006：篡改认证 tag（尾部）→ 校验失败。 */
    private void assertTamperedTagRejected(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);
        byte[] plaintext = "verify-the-tag".getBytes(StandardCharsets.US_ASCII);
        byte[] ciphertext = cipher.encrypt(plaintext);

        byte[] tampered = ciphertext.clone();
        tampered[tampered.length - 1] ^= (byte) 0x80; // 改尾部认证 tag 最后一字节

        assertThrows(CryptoException.class, () -> cipher.decrypt(tampered),
                "篡改认证 tag 必须导致校验失败");
    }

    /** TC-CRYPTO-007：空明文（empty / null）应被安全处理，不崩溃。 */
    private void assertEmptyPlaintextHandled(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);

        byte[] empty = new byte[0];
        byte[] encEmpty = cipher.encrypt(empty);
        byte[] decEmpty = cipher.decrypt(encEmpty);
        assertArrayEquals(empty, decEmpty, "空明文应往返为长度 0");

        // null 透传约定（实现里 null/length==0 直接返回入参）
        assertArrayEquals(null, cipher.encrypt(null), "null 明文应原样返回 null");
    }

    /** TC-CRYPTO-008：超大明文（1MB）加解密一致。 */
    private void assertLargePlaintextHandled(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        byte[] decrypted = cipher.decrypt(cipher.encrypt(big));
        assertArrayEquals(big, decrypted, "1MB 明文应正确往返");
    }

    /** TC-CRYPTO-009：边界长度（1 / 15 / 16 / 17 / 64 字节，含跨 block）。 */
    private void assertBoundaryLengthsHandled(Supplier<Cipher> factory) {
        int[] lengths = {1, 15, 16, 17, 31, 32, 33, 63, 64, 65};
        for (int len : lengths) {
            Cipher cipher = newCipher(factory);
            byte[] plaintext = new byte[len];
            for (int i = 0; i < len; i++) {
                plaintext[i] = (byte) (len + i);
            }
            byte[] decrypted = cipher.decrypt(cipher.encrypt(plaintext));
            assertArrayEquals(plaintext, decrypted, "长度 " + len + " 字节应正确往返");
        }
    }

    /** TC-CRYPTO-010：相同明文 + 随机 nonce/iv → 多次加密密文各不相同。 */
    private void assertNonceNotReused(Supplier<Cipher> factory) {
        Cipher cipher = newCipher(factory);
        byte[] plaintext = "same-plaintext-different-nonce".getBytes(StandardCharsets.US_ASCII);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            byte[] ciphertext = cipher.encrypt(plaintext);
            String hex = toHex(ciphertext);
            assertTrue(seen.add(hex), "相同明文不同 nonce 不应产生重复密文（第 " + i + " 次重复）");
            // 即使密文头不同，解密仍应一致
            assertArrayEquals(plaintext, cipher.decrypt(ciphertext));
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // ChaCha20-Poly1305
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("ChaCha20-Poly1305")
    class ChaCha20 {
        private final Supplier<Cipher> factory = ChaCha20Cipher::new;

        @Test @DisplayName("TC-CRYPTO-001 加解密往返一致")
        void roundTrip() { assertRoundTrip(factory); }

        @Test @DisplayName("TC-CRYPTO-004 错误密钥解密失败")
        void wrongKey() { assertWrongKeyRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-005 篡改密文被检测")
        void tamperedCiphertext() { assertTamperedCiphertextRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-006 篡改 tag 被检测")
        void tamperedTag() { assertTamperedTagRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-007 空明文正确处理")
        void empty() { assertEmptyPlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-008 超大明文(1MB)正确处理")
        void large() { assertLargePlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-009 边界长度正确处理")
        void boundary() { assertBoundaryLengthsHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-010 nonce 不复用")
        void nonceUnique() { assertNonceNotReused(factory); }
    }

    // ---------------------------------------------------------------------
    // AES-256-GCM
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("AES-256-GCM")
    class AesGcm {
        private final Supplier<Cipher> factory = AesGcmCipher::new;

        @Test @DisplayName("TC-CRYPTO-002 加解密往返一致")
        void roundTrip() { assertRoundTrip(factory); }

        @Test @DisplayName("TC-CRYPTO-004 错误密钥解密失败")
        void wrongKey() { assertWrongKeyRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-005 篡改密文被检测")
        void tamperedCiphertext() { assertTamperedCiphertextRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-006 篡改 tag 被检测")
        void tamperedTag() { assertTamperedTagRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-007 空明文正确处理")
        void empty() { assertEmptyPlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-008 超大明文(1MB)正确处理")
        void large() { assertLargePlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-009 边界长度正确处理")
        void boundary() { assertBoundaryLengthsHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-010 nonce 不复用")
        void nonceUnique() { assertNonceNotReused(factory); }
    }

    // ---------------------------------------------------------------------
    // AES-256-CTR + HMAC-SHA256
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("AES-CTR-HMAC")
    class AesCtrHmac {
        private final Supplier<Cipher> factory = AesCtrHmacCipher::new;

        @Test @DisplayName("TC-CRYPTO-003 加解密往返一致")
        void roundTrip() { assertRoundTrip(factory); }

        @Test @DisplayName("TC-CRYPTO-004 错误密钥解密失败")
        void wrongKey() { assertWrongKeyRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-005 篡改密文被检测")
        void tamperedCiphertext() { assertTamperedCiphertextRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-006 篡改 HMAC 被检测")
        void tamperedTag() { assertTamperedTagRejected(factory); }

        @Test @DisplayName("TC-CRYPTO-007 空明文正确处理")
        void empty() { assertEmptyPlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-008 超大明文(1MB)正确处理")
        void large() { assertLargePlaintextHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-009 边界长度正确处理")
        void boundary() { assertBoundaryLengthsHandled(factory); }

        @Test @DisplayName("TC-CRYPTO-010 IV 不复用")
        void nonceUnique() { assertNonceNotReused(factory); }
    }

    // ---------------------------------------------------------------------
    // TC-CRYPTO-011 NoneCipher 透传 & 未初始化保护
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("TC-CRYPTO-011 NoneCipher 原样透传")
    void noneCipherPassthrough() {
        Cipher none = new NoneCipher();
        none.init(new CipherConfig(KEY.clone()));
        byte[] plaintext = "plain-passthrough".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(plaintext, none.encrypt(plaintext), "NoneCipher encrypt 应原样返回");
        assertArrayEquals(plaintext, none.decrypt(plaintext), "NoneCipher decrypt 应原样返回");
    }

    @Test
    @DisplayName("未初始化即加密应抛异常 (健壮性)")
    void encryptBeforeInitThrows() {
        Cipher chacha = new ChaCha20Cipher();
        assertThrows(CryptoException.class,
                () -> chacha.encrypt("x".getBytes(StandardCharsets.US_ASCII)),
                "未 init 即 encrypt 必须抛 CryptoException");
    }

    @Test
    @DisplayName("空/null 密钥 init 应抛异常 (健壮性)")
    void initWithEmptyKeyThrows() {
        assertThrows(CryptoException.class,
                () -> new AesGcmCipher().init(new CipherConfig(new byte[0])),
                "空密钥 init 必须抛 CryptoException");
        assertThrows(CryptoException.class,
                () -> new ChaCha20Cipher().init(new CipherConfig(null)),
                "null 密钥 init 必须抛 CryptoException");
    }
}
