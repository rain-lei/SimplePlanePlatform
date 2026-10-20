package com.proxy.crypto;

import com.proxy.common.crypto.CryptoException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standard primitives only; no transport or application dependencies. */
final class AeadSupport {
    private AeadSupport() { }

    static byte[] derive(byte[] master, byte[] salt, int suite, int direction) {
        byte[] prefix = "simpleplane/aead/v2/".getBytes(StandardCharsets.US_ASCII);
        byte[] info = Arrays.copyOf(prefix, prefix.length + 2);
        info[prefix.length] = (byte) suite;
        info[prefix.length + 1] = (byte) direction;
        return hkdf(master, salt, info, 32);
    }

    static byte[] hkdf(byte[] master, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(master, salt, info));
        byte[] key = new byte[length];
        hkdf.generateBytes(key, 0, key.length);
        return key;
    }

    static byte[] crypt(boolean encrypt, int suite, byte[] key, byte[] nonce,
                        byte[] aad, byte[] input) {
        try {
            if (suite == 1) {
                ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
                cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
                byte[] output = new byte[cipher.getOutputSize(input.length)];
                int count = cipher.processBytes(input, 0, input.length, output, 0);
                count += cipher.doFinal(output, count);
                return count == output.length ? output : Arrays.copyOf(output, count);
            }
            if (suite == 2) {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(encrypt ? javax.crypto.Cipher.ENCRYPT_MODE : javax.crypto.Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(aad);
                return cipher.doFinal(input);
            }
            throw new CryptoException("Unknown AEAD suite");
        } catch (Exception e) {
            throw new CryptoException("AEAD operation failed", e);
        }
    }
}
