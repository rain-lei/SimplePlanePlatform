package com.proxy.transport.netty.codec;

import com.proxy.common.codec.CodecException;
import com.proxy.common.model.ProxyMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 协议编解码往返单元测试，覆盖《项目测试计划与清单》§4.4 TC-EXCHANGE-001 / TC-TRANS-001，
 * 以及 TC-TRANS-002（半包：不完整字节必须被拒绝而非误读）。
 * <p>
 * {@link ProxyCodec} 是自定义二进制帧（固定头 28 字节 + 变长 host + 变长 data）的纯函数实现，
 * 是整条 local↔remote 协议链路的字节级地基。本测试穷举各消息类型、含/不含 host、含/不含 data、
 * 二进制载荷与边界值，断言 encode → decode 后所有字段逐一还原；并验证截断输入会抛
 * {@link CodecException}，从而锁定“不会把残缺帧当成合法消息”这一健壮性契约。
 * </p>
 */
@DisplayName("ProxyCodec 编解码往返 (TC-TRANS-001 / TC-EXCHANGE-001)")
public class ProxyCodecTest {

    private final ProxyCodec codec = new ProxyCodec();

    /** 断言一条消息 encode→decode 后关键字段全部还原。 */
    private void assertRoundTrip(ProxyMessage msg) {
        byte[] encoded = codec.encode(msg);
        ProxyMessage decoded = codec.decode(encoded);

        assertEquals(msg.getType(), decoded.getType(), "type 应还原");
        assertEquals(msg.getStatus(), decoded.getStatus(), "status 应还原");
        assertEquals(msg.getRequestId(), decoded.getRequestId(), "requestId 应还原");
        assertEquals(msg.getStreamId(), decoded.getStreamId(), "streamId 应还原");
        assertEquals(msg.getHost(), decoded.getHost(), "host 应还原");
        assertEquals(msg.getPort(), decoded.getPort(), "port 应还原");

        byte[] expectData = msg.getData();
        byte[] actualData = decoded.getData();
        if (expectData == null || expectData.length == 0) {
            // 编码侧把 null/empty data 统一写为 dataLen=0，解码侧还原为 null
            assertNull(actualData, "空 data 应解码为 null");
        } else {
            assertArrayEquals(expectData, actualData, "data 应逐字节还原");
        }
    }

    @Test
    @DisplayName("TC-TRANS-001 CONNECT 报文（含 host/port）往返")
    void roundTripConnect() {
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .requestId(123456789L)
                .streamId(42L)
                .host("www.example.com")
                .port(443)
                .status(0)
                .build();
        assertRoundTrip(msg);
    }

    @Test
    @DisplayName("TC-TRANS-001 DATA 报文（二进制载荷）往返")
    void roundTripDataBinary() {
        byte[] payload = new byte[512];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 13 + 7);
        }
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .requestId(Long.MAX_VALUE)
                .streamId(Long.MIN_VALUE)
                .data(payload)
                .port(0)
                .build();
        assertRoundTrip(msg);
    }

    @Test
    @DisplayName("TC-TRANS-001 所有 MessageType 均可往返")
    void roundTripAllTypes() {
        for (ProxyMessage.MessageType type : ProxyMessage.MessageType.values()) {
            ProxyMessage msg = ProxyMessage.builder()
                    .type(type)
                    .requestId(7L)
                    .streamId(8L)
                    .host("h.test")
                    .port(8080)
                    .status(200)
                    .data("payload-for-".concat(type.name()).getBytes(StandardCharsets.UTF_8))
                    .build();
            assertRoundTrip(msg);
        }
    }

    @Test
    @DisplayName("TC-TRANS-001 无 host、无 data 的最小消息往返")
    void roundTripMinimal() {
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.HEARTBEAT_REQUEST)
                .requestId(1L)
                .streamId(0L)
                .port(0)
                .build();
        assertRoundTrip(msg);
    }

    @Test
    @DisplayName("TC-TRANS-001 UTF-8 域名（含中文/长域名）往返")
    void roundTripUtf8Host() {
        StringBuilder longHost = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longHost.append("子域").append(i).append('.');
        }
        longHost.append("例子.cn");
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .requestId(99L)
                .streamId(1L)
                .host(longHost.toString())
                .port(65535)
                .build();
        assertRoundTrip(msg);
    }

    @Test
    @DisplayName("TC-TRANS-002 截断到头部以下 → decode 抛 CodecException")
    void decodeTruncatedHeaderRejected() {
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .requestId(1L).streamId(1L).host("a.b").port(80).build();
        byte[] encoded = codec.encode(msg);

        // 砍到固定头(28B)以下
        byte[] truncated = Arrays.copyOf(encoded, 10);
        assertThrows(CodecException.class, () -> codec.decode(truncated),
                "头部不完整必须抛 CodecException，不能误判为合法消息");
    }

    @Test
    @DisplayName("TC-TRANS-002 host 声明长度但字节不足 → decode 抛 CodecException")
    void decodeIncompleteHostRejected() {
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.CONNECT)
                .requestId(1L).streamId(1L).host("a-rather-long-host.example.com").port(80).build();
        byte[] encoded = codec.encode(msg);

        // 保留固定头 + 部分 host（声明的 hostLen > 实际剩余字节）
        byte[] truncated = Arrays.copyOf(encoded, 28 + 3);
        assertThrows(CodecException.class, () -> codec.decode(truncated),
                "host 数据不完整必须抛 CodecException");
    }

    @Test
    @DisplayName("TC-TRANS-002 data 声明长度但字节不足 → decode 抛 CodecException")
    void decodeIncompletePayloadRejected() {
        byte[] payload = new byte[100];
        Arrays.fill(payload, (byte) 0x5A);
        ProxyMessage msg = ProxyMessage.builder()
                .type(ProxyMessage.MessageType.DATA)
                .requestId(1L).streamId(1L).data(payload).port(0).build();
        byte[] encoded = codec.encode(msg);

        // 砍掉一半 payload，使声明的 dataLen 大于剩余字节
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 50);
        assertThrows(CodecException.class, () -> codec.decode(truncated),
                "payload 数据不完整必须抛 CodecException");
    }

    @Test
    @DisplayName("编码 null 消息抛 CodecException（健壮性）")
    void encodeNullRejected() {
        assertThrows(CodecException.class, () -> codec.encode(null));
    }

    @Test
    @DisplayName("解码 null/空字节抛 CodecException（健壮性）")
    void decodeNullRejected() {
        assertThrows(CodecException.class, () -> codec.decode(null));
        assertThrows(CodecException.class, () -> codec.decode(new byte[0]));
    }
}
