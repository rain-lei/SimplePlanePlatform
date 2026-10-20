package com.proxy.transport.netty.handler;

import com.proxy.common.crypto.Cipher;
import com.proxy.common.crypto.CipherConfig;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class CipherFramingTest {
    private Cipher cipher(String suite, boolean server) {
        Cipher cipher = ExtensionLoader.getLoader(Cipher.class).newExtension(suite);
        CipherConfig config = new CipherConfig("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        config.setServerSide(server);
        cipher.init(config);
        return cipher;
    }

    @Test
    void realHandlersHandleOneByteSplitsCoalescingLargeRecordsAndBothDirections() throws Exception {
        for (String suite : new String[]{"chacha20-poly1305-v2", "aes-gcm-v2", "chacha20", "aes-gcm", "aes-ctr-hmac"}) {
            for (boolean server : new boolean[]{false, true}) {
                EmbeddedChannel encoder = new EmbeddedChannel(new CipherEncodeHandler(cipher(suite, server)));
                EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecodeHandler(cipher(suite, !server)));
                try {
                    for (int chunk : new int[]{1, 7, 16384, 65536}) {
                        byte[] a = new byte[16513]; new Random(chunk).nextBytes(a);
                        byte[] b = new byte[1024 * 1024 + 33]; new Random(chunk + 1).nextBytes(b);
                        ByteArrayOutputStream wire = new ByteArrayOutputStream();
                        for (byte[] payload : new byte[][]{a, b}) {
                            encoder.writeOutbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(payload)));
                            Http2DataFrame frame = encoder.readOutbound();
                            byte[] bytes = new byte[frame.content().readableBytes()]; frame.content().readBytes(bytes);
                            wire.write(bytes); frame.release();
                        }
                        byte[] all = wire.toByteArray();
                        // Keep byte-wise test bounded while covering every header/tag boundary.
                        int offset = 0;
                        while (offset < all.length) {
                            int step = chunk == 1 && offset > 16600 ? 16384 : chunk;
                            int length = Math.min(step, all.length - offset);
                            decoder.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(Arrays.copyOfRange(all, offset, offset + length))));
                            offset += length;
                        }
                        for (byte[] expected : new byte[][]{a, b}) {
                            Http2DataFrame decoded = decoder.readInbound();
                            assertNotNull(decoded);
                            byte[] bytes = new byte[decoded.content().readableBytes()]; decoded.content().readBytes(bytes);
                            assertArrayEquals(expected, bytes, suite); decoded.release();
                        }
                        assertNull(decoder.readInbound());
                    }
                } finally { encoder.finishAndReleaseAll(); decoder.finishAndReleaseAll(); }
            }
        }
    }

    @Test
    void invalidLengthsAndTruncatedEndStreamCloseWithoutOutput() {
        for (int length : new int[]{-1, Integer.MAX_VALUE, 8 * 1024 * 1024 + 65537}) {
            EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecodeHandler(cipher("chacha20-poly1305-v2", true)));
            try {
                decoder.writeInbound(new DefaultHttp2DataFrame(Unpooled.buffer(4).writeInt(length)));
                assertFalse(decoder.isActive()); assertNull(decoder.readInbound());
            } finally { decoder.finishAndReleaseAll(); }
        }
        EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecodeHandler(cipher("chacha20-poly1305-v2", true)));
        try {
            decoder.writeInbound(new DefaultHttp2DataFrame(Unpooled.buffer(6).writeInt(100).writeShort(0), true));
            assertFalse(decoder.isActive()); assertNull(decoder.readInbound());
        } finally { decoder.finishAndReleaseAll(); }
    }

    @Test
    void authenticationFailureNeverPublishesPlaintext() {
        byte[] record = cipher("chacha20-poly1305-v2", false).encrypt(new byte[80]);
        record[record.length - 1] ^= 1;
        EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecodeHandler(cipher("chacha20-poly1305-v2", true)));
        try {
            decoder.writeInbound(new DefaultHttp2DataFrame(Unpooled.buffer(4 + record.length).writeInt(record.length).writeBytes(record)));
            assertFalse(decoder.isActive()); assertNull(decoder.readInbound());
        } finally { decoder.finishAndReleaseAll(); }
    }
}
