package com.proxy.crypto;

import com.proxy.common.crypto.CipherConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-CRYPTO-012：跨实现/回归测试向量复算。
 * <p>
 * 复用 {@code docs/design/crypto-vectors.json}（由 {@link CryptoVectorGenTest} 生成的权威产物，
 * 也供 Rust 侧 {@code plane-core/src/crypto.rs} 比对）。本测试把每条向量的 {@code full_output_hex}
 * 喂给生产实现 {@link ChaCha20Cipher#decrypt}，断言能解出 {@code plaintext_hex}，
 * 从而锁定 ChaCha20-Poly1305 密文格式（nonce|ct|tag）与解密行为不发生回归。
 * </p>
 * <p>
 * 不引入 JSON 依赖（proxy-crypto 仅有 BouncyCastle/slf4j），用正则做最小解析。
 * </p>
 */
@DisplayName("加密测试向量复算 (TC-CRYPTO-012)")
public class CryptoVectorReplayTest {

    /** 向量文件相对仓库根的路径；测试 CWD = proxy-crypto 模块目录，回退一级。 */
    private static final String VECTORS_RELATIVE = "../docs/design/crypto-vectors.json";

    private static final class Vector {
        String name;
        byte[] rawKey;
        byte[] plaintext;
        byte[] fullOutput;
    }

    @Test
    @DisplayName("逐条向量：decrypt(full_output) 还原 plaintext")
    void replayVectors() throws IOException {
        Path path = Paths.get(VECTORS_RELATIVE).toAbsolutePath().normalize();
        assertTrue(Files.exists(path),
                "向量文件不存在: " + path + "（请先运行 CryptoVectorGenTest 生成）");

        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        List<Vector> vectors = parseVectors(json);
        assertFalse(vectors.isEmpty(), "向量文件中未解析到任何向量");

        for (Vector v : vectors) {
            ChaCha20Cipher cipher = new ChaCha20Cipher();
            cipher.init(new CipherConfig(v.rawKey));
            byte[] decrypted = cipher.decrypt(v.fullOutput);
            assertArrayEquals(v.plaintext, decrypted,
                    "向量 [" + v.name + "] 解密结果与期望明文不一致（密文格式或解密行为发生回归）");
        }
    }

    /** 极简正则解析：按字段名抓取 hex 串，配对组装为向量列表。 */
    private List<Vector> parseVectors(String json) {
        List<Vector> result = new ArrayList<>();
        Pattern objPattern = Pattern.compile("\\{[^}]*\\}", Pattern.DOTALL);
        Matcher objMatcher = objPattern.matcher(json);
        while (objMatcher.find()) {
            String obj = objMatcher.group();
            String name = field(obj, "name");
            String rawKey = field(obj, "raw_key_hex");
            String plaintext = field(obj, "plaintext_hex");
            String full = field(obj, "full_output_hex");
            if (rawKey == null || plaintext == null || full == null) {
                continue; // 跳过非向量对象（如文件头）
            }
            Vector v = new Vector();
            v.name = name != null ? name : "(unnamed)";
            v.rawKey = fromHex(rawKey);
            v.plaintext = fromHex(plaintext);
            v.fullOutput = fromHex(full);
            result.add(v);
        }
        return result;
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([0-9a-fA-F]*)\"")
                .matcher(obj);
        if (m.find()) {
            return m.group(1);
        }
        // name 字段是非 hex 字符串，单独再试一次
        Matcher m2 = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(obj);
        return m2.find() ? m2.group(1) : null;
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
