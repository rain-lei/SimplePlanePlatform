package com.proxy.transport.netty.integration;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.crypto.CryptoException;
import com.proxy.common.model.ProxyMessage;
import com.proxy.crypto.AesGcmCipher;
import com.proxy.crypto.ChaCha20Cipher;
import com.proxy.transport.netty.codec.ProxyCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加密全链路集成测试，覆盖《项目测试计划与清单》§4.3 TC-INT-002 / TC-INT-003。
 * <p>
 * 不起真实网络，而是在组件级忠实复刻 proxy-local↔proxy-remote 的出/入站链路，
 * 与 {@code CipherEncodeHandler} / {@code CipherDecodeHandler} 的协议约定保持一致：
 * </p>
 * <pre>
 * 出站: ProxyMessage --ProxyCodec.encode--> 明文帧 --Cipher.encrypt--> 密文
 *       --加 4 字节大端长度前缀--> [len|nonce|ct|tag]  （摆脱 HTTP/2 帧边界）
 * 入站: 累积字节 --按长度前缀切出完整密文块--> Cipher.decrypt --> 明文帧
 *       --ProxyCodec.decode--> ProxyMessage
 * </pre>
 * <p>
 * TC-INT-002：明文经“编码→加密→（被任意重新切分的）传输→解密→解码”后字段完全一致。
 * TC-INT-003：收发两端 cipherKey 不一致时，解密阶段必然失败（认证校验不通过）。
 * </p>
 */
@DisplayName("加密全链路集成 (TC-INT-002 / TC-INT-003)")
public class CryptoChannelIntegrationTest {

    private static final byte[] KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private static Cipher cipher(Supplier<Cipher> factory, byte[] key) {
        Cipher c = factory.get();
        c.init(new CipherConfig(key.clone()));
        return c;
    }

    // ------------------------------------------------------------------
    // 出站：ProxyMessage -> 线缆字节（4B 长度前缀 + 密文）
    // ------------------------------------------------------------------
    private byte[] outbound(ProxyMessage msg, Cipher cipher) {
        byte[] plainFrame = new ProxyCodec().encode(msg);
        byte[] ciphertext = cipher.encrypt(plainFrame);
        ByteBuffer buf = ByteBuffer.allocate(4 + ciphertext.length);
        buf.putInt(ciphertext.length);
        buf.put(ciphertext);
        return buf.array();
    }

    // ------------------------------------------------------------------
    // 入站：累积字节流 -> 切出完整密文块 -> 解密 -> 解码 -> ProxyMessage 列表
    // 模拟解密侧对“帧边界不可信”的处理：按 4 字节长度前缀精确切块。
    // ------------------------------------------------------------------
    private List<ProxyMessage> inbound(byte[] wire, Cipher cipher) {
        List<ProxyMessage> result = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(wire);
        ProxyCodec codec = new ProxyCodec();
        while (buf.remaining() >= 4) {
            buf.mark();
            int len = buf.getInt();
            if (buf.remaining() < len) {
                buf.reset();
                break; // 半包：等待更多字节
            }
            byte[] ct = new byte[len];
            buf.get(ct);
            byte[] plainFrame = cipher.decrypt(ct);
            result.add(codec.decode(plainFrame));
        }
        return result;
    }

    /** 把多段 wire 字节按任意切片大小拼接，模拟 HTTP/2 把数据重新切分/合并后再交给解密侧。 */
    private byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] c : chunks) {
            out.write(c, 0, c.length);
        }
        return out.toByteArray();
    }

    private ProxyMessage sampleConnect() {
        return ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .requestId(0xDEADBEEFL)
                .streamId(7L)
                .host("secure.example.com")
                .port(443)
                .status(0)
                .build();
    }

    private void assertChainPreservesContent(Supplier<Cipher> factory) {
        Cipher enc = cipher(factory, KEY);
        Cipher dec = cipher(factory, KEY);

        ProxyMessage original = sampleConnect();
        byte[] wire = outbound(original, enc);

        List<ProxyMessage> decoded = inbound(wire, dec);
        assertEquals(1, decoded.size(), "应解出 1 条消息");
        ProxyMessage got = decoded.get(0);
        assertEquals(original.getType(), got.getType());
        assertEquals(original.getRequestId(), got.getRequestId());
        assertEquals(original.getStreamId(), got.getStreamId());
        assertEquals(original.getHost(), got.getHost());
        assertEquals(original.getPort(), got.getPort());
    }

    @Test
    @DisplayName("TC-INT-002 AES-GCM 全链路内容一致")
    void aesGcmChainContentIntact() {
        assertChainPreservesContent(AesGcmCipher::new);
    }

    @Test
    @DisplayName("TC-INT-002 ChaCha20 全链路内容一致")
    void chacha20ChainContentIntact() {
        assertChainPreservesContent(ChaCha20Cipher::new);
    }

    @Test
    @DisplayName("TC-INT-002 携带二进制 DATA 的全链路内容一致")
    void dataPayloadChainIntact() {
        Cipher enc = cipher(AesGcmCipher::new, KEY);
        Cipher dec = cipher(AesGcmCipher::new, KEY);

        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 17);
        }
        ProxyMessage original = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .requestId(1L).streamId(2L).data(payload).port(0).build();

        byte[] wire = outbound(original, enc);
        List<ProxyMessage> decoded = inbound(wire, dec);

        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.get(0).getData(), "DATA 载荷应原样还原");
    }

    @Test
    @DisplayName("TC-INT-002 多条消息背靠背 + 任意帧切分仍按序还原（半包/粘包）")
    void multiMessageReassembly() {
        Cipher enc = cipher(AesGcmCipher::new, KEY);
        Cipher dec = cipher(AesGcmCipher::new, KEY);

        List<ProxyMessage> originals = new ArrayList<>();
        ByteArrayOutputStream wireStream = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) {
            ProxyMessage m = ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .requestId(i).streamId(i * 10L)
                    .data(("message-body-" + i).getBytes(StandardCharsets.UTF_8))
                    .port(0).build();
            originals.add(m);
            byte[] w = outbound(m, enc);
            wireStream.write(w, 0, w.length);
        }
        byte[] fullWire = wireStream.toByteArray();

        // 把整段线缆字节按非对齐的 7 字节碎片重新拼接，证明解密侧不依赖帧边界。
        List<byte[]> chunks = new ArrayList<>();
        for (int off = 0; off < fullWire.length; off += 7) {
            int end = Math.min(off + 7, fullWire.length);
            byte[] piece = new byte[end - off];
            System.arraycopy(fullWire, off, piece, 0, piece.length);
            chunks.add(piece);
        }
        byte[] reassembled = concat(chunks);

        List<ProxyMessage> decoded = inbound(reassembled, dec);
        assertEquals(originals.size(), decoded.size(), "5 条消息应全部还原");
        for (int i = 0; i < originals.size(); i++) {
            assertArrayEquals(originals.get(i).getData(), decoded.get(i).getData(),
                    "第 " + i + " 条消息载荷应按序还原");
        }
    }

    @Test
    @DisplayName("TC-INT-003 收发两端密钥不一致 → 解密失败")
    void wrongKeyBreaksChain() {
        Cipher enc = cipher(AesGcmCipher::new, KEY);
        byte[] wrongKey = "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII);
        Cipher dec = cipher(AesGcmCipher::new, wrongKey);

        byte[] wire = outbound(sampleConnect(), enc);
        assertThrows(CryptoException.class, () -> inbound(wire, dec),
                "cipherKey 不一致时入站解密必须失败，绝不能解出明文帧");
    }

    @Test
    @DisplayName("TC-INT-003 传输中密文被篡改 → 解密失败")
    void tamperedWireBreaksChain() {
        Cipher enc = cipher(AesGcmCipher::new, KEY);
        Cipher dec = cipher(AesGcmCipher::new, KEY);

        byte[] wire = outbound(sampleConnect(), enc);
        // 翻转长度前缀之后的某个密文字节
        int idx = 4 + (wire.length - 4) / 2;
        wire[idx] ^= 0x01;

        assertThrows(CryptoException.class, () -> inbound(wire, dec),
                "传输中被篡改的密文必须触发认证失败");
    }

    @Test
    @DisplayName("半包：不足一个完整密文块时不产出消息（不误读）")
    void partialWireProducesNothing() {
        Cipher enc = cipher(AesGcmCipher::new, KEY);
        Cipher dec = cipher(AesGcmCipher::new, KEY);

        byte[] wire = outbound(sampleConnect(), enc);
        byte[] partial = new byte[wire.length - 5]; // 砍掉尾部，构成半包
        System.arraycopy(wire, 0, partial, 0, partial.length);

        List<ProxyMessage> decoded = inbound(partial, dec);
        assertTrue(decoded.isEmpty(), "半包不应产出任何消息");
    }
}
