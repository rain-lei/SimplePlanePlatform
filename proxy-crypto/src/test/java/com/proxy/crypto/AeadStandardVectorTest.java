package com.proxy.crypto;

import com.proxy.common.crypto.CipherConfig;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AeadStandardVectorTest {
    static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Test
    void rfc8439Section282() {
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] plain = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] expected = hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6"
                + "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36"
                + "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3"
                + "ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691");
        assertArrayEquals(expected, AeadSupport.crypt(true, 1, key, nonce, aad, plain));
        assertArrayEquals(plain, AeadSupport.crypt(false, 1, key, nonce, aad, expected));
    }

    @Test
    void rfc5869Case1() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
                AeadSupport.hkdf(ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42));
    }

    @Test
    void nistGcmKnownAnswerAndLegacyWireFormat() {
        byte[] body = hex("0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf");
        assertArrayEquals(body, AeadSupport.crypt(true, 2, new byte[16], new byte[12], new byte[0], new byte[16]));
        AesGcmCipher legacy = new AesGcmCipher();
        legacy.init(new CipherConfig(new byte[16]));
        byte[] wire = new byte[12 + body.length];
        System.arraycopy(body, 0, wire, 12, body.length);
        assertArrayEquals(new byte[16], legacy.decrypt(wire));
    }

    @Test
    void legacyCtrHmacMatchesIndependentOpenSslReference() throws Exception {
        String line = Files.readAllLines(Paths.get("../docs/design/crypto-legacy-ctr-vector.tsv"), StandardCharsets.UTF_8).get(1);
        String[] fields = line.split("\t", -1);
        AesCtrHmacCipher legacy = new AesCtrHmacCipher();
        legacy.init(new CipherConfig(hex(fields[0])));
        byte[] record = hex(fields[2]);
        assertArrayEquals(hex(fields[1]), legacy.decrypt(record));
        record[0] ^= 1;
        assertThrows(com.proxy.common.crypto.CryptoException.class, () -> legacy.decrypt(record));
    }
}
