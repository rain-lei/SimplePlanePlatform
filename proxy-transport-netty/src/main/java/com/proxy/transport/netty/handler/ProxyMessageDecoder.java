package com.proxy.transport.netty.handler;

import com.proxy.common.codec.Codec;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.common.transport.FlowPermit;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * HTTP/2 DATA 帧 → ProxyMessage 解码器（支持跨帧累积）
 * <p>
 * 接受两种上游消息类型：
 * <ul>
 *   <li>{@link PermittedDataFrame} —— 背压开启时由 BackpressureHandler 下发，携带 FlowPermit</li>
 *   <li>{@link Http2DataFrame} —— 背压关闭时由 CipherDecodeHandler 直接下发</li>
 * </ul>
 * </p>
 * <p>
 * 每次解码出完整 {@link ProxyMessage} 时，将当前所有 pending permit 合并后存入
 * Channel Attribute（key = {@code "proxy.flow.permit"}），再通过 fireChannelRead
 * 触发下游 ExchangeHandler。ExchangeHandler 在 channelRead0 中读取并清除该 attr。
 * </p>
 */
public class ProxyMessageDecoder extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyMessageDecoder.class);

    private static final int FIXED_HEADER_SIZE = 28;

    /**
     * Channel Attribute Key —— 与 ExchangeHandler 中的字符串必须完全相同。
     * Netty 的 AttributeKey.valueOf() 按名称复用实例，两处无需共享常量。
     */
    static final AttributeKey<FlowPermit> PERMIT_KEY =
            AttributeKey.valueOf("proxy.flow.permit");

    private final Codec codec;

    /** 累积缓冲区：跨 DATA 帧的消息重组 */
    private CompositeByteBuf cumulation;

    /**
     * 当前帧流中尚未分配给任何 ProxyMessage 的 permit 列表。
     * 每帧入队 1 个，解码出完整消息时全部合并并清空。
     */
    private final Deque<FlowPermit> pendingPermits = new ArrayDeque<>();

    public ProxyMessageDecoder() {
        this.codec = ExtensionLoader.getLoader(Codec.class).getDefaultExtension();
    }

    public ProxyMessageDecoder(Codec codec) {
        this.codec = codec;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 提取帧内容和对应的 permit
        final Http2DataFrame frame;
        final FlowPermit permit;

        if (msg instanceof PermittedDataFrame) {
            PermittedDataFrame pdf = (PermittedDataFrame) msg;
            frame = pdf.frame();
            permit = pdf.permit();
        } else if (msg instanceof Http2DataFrame) {
            frame = (Http2DataFrame) msg;
            permit = FlowPermit.NOOP;
        } else {
            // 非 DATA 帧（HEADERS、RST_STREAM 等）直接透传
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf content = frame.content();
        if (content.readableBytes() == 0) {
            // 空帧：permit 不需要排进队列，直接释放
            permit.release();
            frame.release();
            log.debug("Received empty HTTP/2 DATA frame, skipping");
            return;
        }

        // 将此帧的 permit 加入待归并列表
        pendingPermits.addLast(permit);

        // 将 DATA 帧内容追加到累积缓冲区（retain 后 frame 可安全释放）
        if (cumulation == null) {
            cumulation = ctx.alloc().compositeBuffer(256);
        }
        cumulation.addComponent(true, content.retain());
        // 释放 frame wrapper：content 已被 cumulation 持有，frame 本身不再需要
        frame.release();

        // 尝试解码所有完整消息
        while (cumulation.readableBytes() > 0) {
            int readerIndex = cumulation.readerIndex();

            if (cumulation.readableBytes() < FIXED_HEADER_SIZE) {
                break;
            }

            int hostLen = cumulation.getUnsignedShort(readerIndex + 18);
            int headerTotalLen = FIXED_HEADER_SIZE + hostLen;
            if (cumulation.readableBytes() < headerTotalLen) {
                break;
            }

            int dataLen = cumulation.getInt(readerIndex + 24 + hostLen);
            int totalMessageLen = headerTotalLen + dataLen;
            if (cumulation.readableBytes() < totalMessageLen) {
                break;
            }

            byte[] messageBytes = new byte[totalMessageLen];
            cumulation.readBytes(messageBytes);

            ProxyMessage proxyMsg = codec.decode(messageBytes);
            if (proxyMsg == null) {
                continue;
            }

            // 取出所有 pending permits，合并后绑定到本条消息。
            // 若一帧产生了多条消息（极少见），后续消息拿到 NOOP —— 信用已由第一条消息归还。
            final FlowPermit msgPermit;
            if (!pendingPermits.isEmpty()) {
                msgPermit = FlowPermit.merge(new ArrayList<>(pendingPermits));
                pendingPermits.clear();
            } else {
                msgPermit = FlowPermit.NOOP;
            }

            // 先写入 attr，再 fire —— ExchangeHandler 在同一 EventLoop 线程同步读取
            ctx.channel().attr(PERMIT_KEY).set(msgPermit);
            ctx.fireChannelRead(proxyMsg);

            log.trace("Decoded ProxyMessage: type={}, requestId={}, dataLen={}",
                    proxyMsg.getType(), proxyMsg.getRequestId(),
                    proxyMsg.getData() != null ? proxyMsg.getData().length : 0);
        }

        // 释放已读取部分
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
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releasePendingPermits();
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

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releasePendingPermits();
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
        super.handlerRemoved(ctx);
    }

    private void releasePendingPermits() {
        for (FlowPermit p : pendingPermits) {
            p.release();
        }
        pendingPermits.clear();
    }
}
