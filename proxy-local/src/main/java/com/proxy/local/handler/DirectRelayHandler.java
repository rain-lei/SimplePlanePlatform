package com.proxy.local.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 直连中继 Handler —— 不经过远程代理，直接与目标建立 TCP 连接并双向转发
 * <p>
 * 当路由规则判定某个域名应该直连时，使用此 Handler 代替 RelayHandler。
 * 在浏览器和目标服务器之间建立直接的 TCP 隧道。
 * </p>
 */
public class DirectRelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DirectRelayHandler.class);

    private final String targetHost;
    private final int targetPort;
    private volatile Channel outboundChannel;

    public DirectRelayHandler(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /**
     * 建立到目标服务器的直连，并开始双向转发
     *
     * @param browserCtx 浏览器端的 ChannelHandlerContext
     * @return ChannelFuture 连接完成的 future
     */
    public ChannelFuture connect(ChannelHandlerContext browserCtx) {
        Bootstrap b = new Bootstrap();
        b.group(browserCtx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new BackendHandler(browserCtx));
                    }
                });

        ChannelFuture connectFuture = b.connect(targetHost, targetPort);
        connectFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                outboundChannel = future.channel();
                log.debug("Direct connection established to {}:{}", targetHost, targetPort);
            } else {
                log.warn("Direct connection failed to {}:{}: {}", targetHost, targetPort,
                        future.cause().getMessage());
                browserCtx.close();
            }
        });
        return connectFuture;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(msg);
        } else {
            // 连接还没建好或已断开，释放消息
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                    .addListener(ChannelFutureListener.CLOSE);
        }
        log.debug("Browser disconnected, closing direct connection to {}:{}", targetHost, targetPort);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Direct relay error for {}:{}: {}", targetHost, targetPort, cause.getMessage());
        ctx.close();
    }

    /**
     * 后端 Handler —— 从目标服务器读取数据写回浏览器
     */
    private static class BackendHandler extends ChannelInboundHandlerAdapter {
        private final ChannelHandlerContext browserCtx;

        BackendHandler(ChannelHandlerContext browserCtx) {
            this.browserCtx = browserCtx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (browserCtx.channel().isActive()) {
                browserCtx.writeAndFlush(msg);
            } else {
                if (msg instanceof ByteBuf) {
                    ((ByteBuf) msg).release();
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (browserCtx.channel().isActive()) {
                browserCtx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Direct backend error: {}", cause.getMessage());
            ctx.close();
        }

        private static final Logger log = LoggerFactory.getLogger(BackendHandler.class);
    }
}
