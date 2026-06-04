package com.proxy.transport.netty.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP/2 DATA 帧 → ProxyMessage 解码器（支持跨帧累积）
 * <p>
 * HTTP/2 DATA 帧有最大帧大小限制（默认 SETTINGS_MAX_FRAME_SIZE = 16384 字节），
 * 当一个 ProxyMessage 编码后超过该限制时，会被拆分成多个 DATA 帧。
 * 本解码器维护一个累积缓冲区，正确处理跨帧的消息重组。
 * </p>
 * <p>
 * 协议格式（来自 ProxyCodec）：
 * <pre>
 * 固定头部 28 字节：Type(1) + Status(1) + RequestId(8) + StreamId(8) + HostLen(2) + Port(4) + DataLen(4)
 * 变长部分：Host(hostLen) + Data(dataLen)
 * </pre>
 * </p>
 */
public class ProxyMessageDecoder extends MessageToMessageDecoder<Http2DataFrame> {

    private static final Logger log = LoggerFactory.getLogger(ProxyMessageDecoder.class);

    private static final int FIXED_HEADER_SIZE = 28;

    private final Codec codec;

    /**
     * 累积缓冲区：用于跨 DATA 帧的消息重组
     */
    private CompositeByteBuf cumulation;

    public ProxyMessageDecoder() {
        this.codec = ExtensionLoader.getLoader(Codec.class).getDefaultExtension();
    }

    public ProxyMessageDecoder(Codec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Http2DataFrame frame, List<Object> out) throws Exception {
        ByteBuf content = frame.content();

        if (content.readableBytes() == 0) {
            log.debug("Received empty HTTP/2 DATA frame, skipping");
            return;
        }

        // 将 DATA 帧内容追加到累积缓冲区
        if (cumulation == null) {
            cumulation = ctx.alloc().compositeBuffer(256);
        }
        cumulation.addComponent(true, content.retain());

        // 尝试从累积缓冲区中解码尽可能多的完整消息
        while (cumulation.readableBytes() > 0) {
            int readerIndex = cumulation.readerIndex();

            // 检查是否有足够的字节读取固定头部
            if (cumulation.readableBytes() < FIXED_HEADER_SIZE) {
                break; // 等待更多数据
            }

            // 解析 hostLen（位于偏移 18-19，即 Type(1)+Status(1)+RequestId(8)+StreamId(8) 之后）
            int hostLen = cumulation.getUnsignedShort(readerIndex + 18);

            // 检查是否有足够的字节读取完头部（包含 host）
            // 头部总长 = 28 + hostLen
            int headerTotalLen = FIXED_HEADER_SIZE + hostLen;
            if (cumulation.readableBytes() < headerTotalLen) {
                break; // 等待更多数据
            }

            // 解析 dataLen（位于偏移 28 + hostLen - 4，即 Port(4) + DataLen(4) 中的 DataLen）
            // 准确位置：Type(1)+Status(1)+RequestId(8)+StreamId(8)+HostLen(2)+Host(hostLen)+Port(4) 之后
            // = 20 + hostLen + 4 = 24 + hostLen
            int dataLen = cumulation.getInt(readerIndex + 24 + hostLen);

            // 完整消息长度
            int totalMessageLen = headerTotalLen + dataLen;
            if (cumulation.readableBytes() < totalMessageLen) {
                break; // 等待更多数据
            }

            // 有一个完整的消息，读取并解码
            byte[] messageBytes = new byte[totalMessageLen];
            cumulation.readBytes(messageBytes);

            ProxyMessage msg = codec.decode(messageBytes);
            if (msg != null) {
                out.add(msg);
                log.trace("Decoded ProxyMessage: type={}, requestId={}, dataLen={}",
                        msg.getType(), msg.getRequestId(),
                        msg.getData() != null ? msg.getData().length : 0);
            }
        }

        // 释放已读取的部分
        if (cumulation != null) {
            if (cumulation.readableBytes() == 0) {
                cumulation.release();
                cumulation = null;
            } else {
                cumulation.discardReadComponents();
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (cumulation != null) {
            if (cumulation.readableBytes() > 0) {
                log.warn("Channel inactive with {} bytes remaining in decoder buffer, discarding",
                        cumulation.readableBytes());
            }
            cumulation.release();
            cumulation = null;
        }
        super.channelInactive(ctx);
    }
}
