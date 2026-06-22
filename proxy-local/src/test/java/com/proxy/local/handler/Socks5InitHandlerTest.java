package com.proxy.local.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-LOCAL-001 [P1]：Socks5InitHandler 认证协商单元测试
 * <p>
 * 使用 EmbeddedChannel 模拟 Netty pipeline，验证：
 * 1. 合法 SOCKS5 握手包 → 回复 NO_AUTH 并切换到 ConnectHandler
 * 2. 非法版本号 → 关闭连接
 * 3. 数据不足 → 不回复，等待更多数据
 */
class Socks5InitHandlerTest {

    /**
     * 构造一个最简 Invoker stub（不会真正被调用，因为 InitHandler 只做认证协商）
     */
    private static com.proxy.common.filter.Invoker noopInvoker() {
        return invocation -> java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    // ---- TC-LOCAL-001a：合法 SOCKS5 握手 → 回复 [0x05, 0x00] ----

    @Test
    void testValidAuthNegotiation() {
        EmbeddedChannel ch = new EmbeddedChannel(new Socks5InitHandler(noopInvoker(), null));

        // 客户端发送: VER=0x05, NMETHODS=1, METHODS=[0x00]
        ByteBuf request = Unpooled.buffer(3);
        request.writeByte(0x05);
        request.writeByte(0x01);
        request.writeByte(0x00);
        ch.writeInbound(request);

        // 验证回复
        ByteBuf response = ch.readOutbound();
        assertNotNull(response, "应回复认证响应");
        assertEquals(2, response.readableBytes(), "响应应为 2 字节");
        assertEquals(0x05, response.readByte() & 0xFF, "VER 应为 0x05");
        assertEquals(0x00, response.readByte() & 0xFF, "METHOD 应为 NO_AUTH (0x00)");
        response.release();

        // 验证 pipeline 已切换到 ConnectHandler
        assertNull(ch.pipeline().get(Socks5InitHandler.class),
                "InitHandler 应已从 pipeline 移除");
        assertNotNull(ch.pipeline().get("socks5-connect"),
                "ConnectHandler 应已添加到 pipeline");

        ch.finish();
    }

    // ---- TC-LOCAL-001b：多种认证方法 → 仍选择 NO_AUTH ----

    @Test
    void testMultipleMethodsStillSelectsNoAuth() {
        EmbeddedChannel ch = new EmbeddedChannel(new Socks5InitHandler(noopInvoker(), null));

        // 客户端提供 3 种方法: NO_AUTH(0x00), USERNAME/PASSWORD(0x02), GSSAPI(0x01)
        ByteBuf request = Unpooled.buffer(5);
        request.writeByte(0x05);
        request.writeByte(0x03);
        request.writeByte(0x00);
        request.writeByte(0x02);
        request.writeByte(0x01);
        ch.writeInbound(request);

        ByteBuf response = ch.readOutbound();
        assertNotNull(response);
        assertEquals(0x05, response.readByte() & 0xFF);
        assertEquals(0x00, response.readByte() & 0xFF, "无论客户端提供什么方法，都应选择 NO_AUTH");
        response.release();
        ch.finish();
    }

    // ---- TC-LOCAL-001c：非法版本号 → 关闭连接 ----

    @Test
    void testInvalidVersionClosesChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(new Socks5InitHandler(noopInvoker(), null));

        // 发送 SOCKS4 版本号
        ByteBuf request = Unpooled.buffer(3);
        request.writeByte(0x04); // SOCKS4 版本
        request.writeByte(0x01);
        request.writeByte(0x00);
        ch.writeInbound(request);

        // 不应有回复
        ByteBuf response = ch.readOutbound();
        assertNull(response, "非法版本不应回复");

        // 通道应被关闭
        assertFalse(ch.isActive(), "非法版本应关闭连接");
    }

    // ---- TC-LOCAL-001d：数据不足（只有 1 字节）→ 不回复 ----

    @Test
    void testInsufficientDataNoResponse() {
        EmbeddedChannel ch = new EmbeddedChannel(new Socks5InitHandler(noopInvoker(), null));

        // 只发送 1 字节（不够解析）
        ByteBuf request = Unpooled.buffer(1);
        request.writeByte(0x05);
        ch.writeInbound(request);

        // 不应有回复
        ByteBuf response = ch.readOutbound();
        assertNull(response, "数据不足时不应回复");

        // 通道应保持打开
        assertTrue(ch.isActive(), "数据不足时不应关闭连接");
        ch.finish();
    }
}
