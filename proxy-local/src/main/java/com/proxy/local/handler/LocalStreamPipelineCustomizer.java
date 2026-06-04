package com.proxy.local.handler;

import com.proxy.common.spi.Activate;
import com.proxy.transport.netty.StreamPipelineCustomizer;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * proxy-local 的 StreamPipelineCustomizer 实现
 * <p>
 * 在 Stream Pipeline 的 "handler"（ClientMessageHandler）之前插入
 * {@link ServerPushDispatchHandler}，用于拦截远程服务端推送的 requestId=0
 * 消息，并通过 StreamChannelRegistry 将数据路由回对应的浏览器连接。
 * </p>
 * <p>
 * 通过 SPI 机制注册：
 * {@code META-INF/proxy/com.proxy.transport.netty.StreamPipelineCustomizer}
 * </p>
 */
@Activate
public class LocalStreamPipelineCustomizer implements StreamPipelineCustomizer {

    private static final Logger log = LoggerFactory.getLogger(LocalStreamPipelineCustomizer.class);

    /**
     * ServerPushDispatchHandler 是 @Sharable 的，可以共享同一个实例
     */
    private static final ServerPushDispatchHandler PUSH_HANDLER = new ServerPushDispatchHandler();

    @Override
    public void customize(ChannelPipeline pipeline) {
        // 在 ClientMessageHandler（名为 "handler"）之前插入推送分发 Handler
        pipeline.addBefore("handler", "push-dispatch", PUSH_HANDLER);
        log.debug("Injected ServerPushDispatchHandler before 'handler' in stream pipeline");
    }
}
