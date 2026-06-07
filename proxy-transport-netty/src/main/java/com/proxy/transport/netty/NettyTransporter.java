package com.proxy.transport.netty;

import com.proxy.common.model.URL;
import com.proxy.common.transport.Client;
import com.proxy.common.transport.MessageHandler;
import com.proxy.common.transport.Server;
import com.proxy.common.transport.Transporter;
import com.proxy.common.transport.TransportException;
import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty Transporter 实现 —— 工厂类
 * <p>
 * 职责单一：
 * <ul>
 *   <li>connect()：创建并返回 NettyClient（客户端连接）</li>
 *   <li>bind()：创建并启动 NettyServer（服务端监听）</li>
 * </ul>
 * 上层传入的 MessageHandler 如果同时实现了 ChannelHandler，
 * 则直接挂到 HTTP/2 Stream Pipeline 末端（统一 ExchangeHandler 方案）。
 * </p>
 */
public class NettyTransporter implements Transporter {

    private static final Logger log = LoggerFactory.getLogger(NettyTransporter.class);

    @Override
    public Client connect(URL url, MessageHandler handler) throws TransportException {
        try {
            NettyClient client = new NettyClient(url, handler);
            log.info("Created NettyClient to {}:{}", url.getHost(), url.getPort());
            return client;
        } catch (Exception e) {
            throw new TransportException("Failed to create NettyClient to " +
                    url.getHost() + ":" + url.getPort(), e);
        }
    }

    @Override
    public Server bind(URL url, MessageHandler handler) throws TransportException {
        try {
            // handler 同时实现 ChannelHandler（如 ExchangeHandler），直接传给 NettyServer
            if (!(handler instanceof ChannelHandler)) {
                throw new IllegalArgumentException(
                        "MessageHandler must also implement ChannelHandler for server-side binding. " +
                        "Actual type: " + handler.getClass().getName());
            }
            NettyServer server = new NettyServer(url, (ChannelHandler) handler);
            server.start();
            log.info("NettyServer started on {}:{}", url.getHost(), url.getPort());
            return server;
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Failed to start NettyServer on " +
                    url.getHost() + ":" + url.getPort(), e);
        }
    }
}
