import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Real Java client -> proxy-remote -> TCP echo probe. Key is read from a file, never printed. */
public final class CryptoInteropProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 8 && args.length != 9) throw new IllegalArgumentException("host port suite key-file target-host target-port streams bytes-per-stream [expect-reject]");
        String configFile = System.getProperty("crypto.probe.remoteConfigFile");
        String key;
        if (configFile == null) {
            key = new String(Files.readAllBytes(Paths.get(args[3])), StandardCharsets.UTF_8).trim();
        } else {
            try (java.io.InputStream input = Files.newInputStream(Paths.get(configFile))) {
                Map<?, ?> config = new org.yaml.snakeyaml.Yaml().load(input);
                key = String.valueOf(config.get("cipherKey"));
            }
        }
        URL url = new URL("proxy", args[0], Integer.parseInt(args[1]))
                .addParameter("cipher", args[2]).addParameter("cipherKey", key)
                .addParameter("connectionsPerNode", 1).addParameter("ioThreads", 2);
        url.addParameter("transport", System.getProperty("crypto.probe.transport", "http2"));
        url.addParameter("http3.caFile", System.getProperty("crypto.probe.caFile", ""));
        url.addParameter("http3.serverName", System.getProperty("crypto.probe.serverName", args[0]));
        int count = Integer.parseInt(args[6]);
        int length = Integer.parseInt(args[7]);
        int chunkBytes = Integer.getInteger("crypto.probe.chunkBytes", 16385);
        ExchangeClient client = null;
        List<Channel> captures = new ArrayList<>();
        EventLoopGroup captureGroup = new DefaultEventLoopGroup(1);
        Channel captureServer = null;
        long streamBase = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - count);
        try {
            client = ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension().connect(url);
            if (args.length == 9 && args[8].equals("expect-reject")) {
                try {
                    client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.CONNECT)
                            .streamId(999).host(args[4]).port(Integer.parseInt(args[5])).build(), 3000)
                            .get(5, TimeUnit.SECONDS);
                    throw new AssertionError("Mismatched cipher/key unexpectedly accepted");
                } catch (ExecutionException | TimeoutException expected) {
                    System.out.println("CRYPTO_REJECTION_OK suite=" + args[2]);
                    return;
                }
            }
            ConcurrentHashMap<Long, ChannelHandlerContext> registry = new ConcurrentHashMap<>();
            client.setStreamRegistry(registry);
            LocalAddress captureAddress = new LocalAddress("crypto-probe-" + UUID.randomUUID());
            captureServer = new ServerBootstrap().group(captureGroup).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override protected void initChannel(LocalChannel channel) { }
                    }).bind(captureAddress).sync().channel();
            List<BlockingQueue<byte[]>> queues = new ArrayList<>();
            List<byte[]> payloads = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long stream = streamBase + i;
                BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
                queues.add(queue);
                Channel capture = new Bootstrap().group(captureGroup).channel(LocalChannel.class)
                        .handler(new ChannelInitializer<LocalChannel>() {
                    @Override protected void initChannel(LocalChannel channel) {
                        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        if (msg instanceof ByteBuf) {
                            ByteBuf bytes = (ByteBuf) msg;
                            byte[] value = new byte[bytes.readableBytes()]; bytes.readBytes(value); bytes.release();
                            queue.add(value); promise.setSuccess();
                        } else { ctx.write(msg, promise); }
                    }
                        }, new ChannelInboundHandlerAdapter());
                    }
                }).connect(captureAddress).sync().channel();
                captures.add(capture);
                registry.put(stream, capture.pipeline().lastContext());
                Response response = client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.CONNECT)
                        .streamId(stream).host(args[4]).port(Integer.parseInt(args[5])).build(), 15000)
                        .get(20, TimeUnit.SECONDS);
                if (!response.isSuccess()) throw new AssertionError("CONNECT failed for stream " + stream);
                byte[] plain = new byte[length]; new Random(1000 + i).nextBytes(plain); payloads.add(plain);
            }
            MessageDigest[] received = new MessageDigest[count];
            int[] receivedBytes = new int[count];
            for (int i = 0; i < count; i++) received[i] = MessageDigest.getInstance("SHA-256");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            // Bounded in-flight batches: this tests crypto, not an unbounded producer flooding an SSH tunnel.
            for (int offset = 0; offset < length; offset += chunkBytes) {
                for (int i = 0; i < count; i++) {
                    byte[] payload = Arrays.copyOfRange(payloads.get(i), offset, Math.min(length, offset + chunkBytes));
                    client.send(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(streamBase + i)
                            .data(payload).port(0).build());
                }
                int expectedEnd = Math.min(length, offset + chunkBytes);
                for (int i = 0; i < count; i++) {
                    while (receivedBytes[i] < expectedEnd && System.nanoTime() < deadline) {
                        byte[] part = queues.get(i).poll(20, TimeUnit.MILLISECONDS);
                        if (part != null) { received[i].update(part); receivedBytes[i] += part.length; }
                    }
                    if (receivedBytes[i] != expectedEnd) throw new AssertionError("echo stalled or overran stream " + (i + 1)
                            + " received=" + receivedBytes[i] + " expected=" + expectedEnd);
                }
                if ((offset / chunkBytes) % 32 == 31) System.out.println("CRYPTO_PROGRESS suite=" + args[2] + " bytes=" + ((long) expectedEnd * count));
            }
            for (int i = 0; i < count; i++) {
                byte[] expected = MessageDigest.getInstance("SHA-256").digest(payloads.get(i));
                if (receivedBytes[i] != length || !Arrays.equals(expected, received[i].digest())) {
                    throw new AssertionError("stream " + (i + 1) + " bytes=" + receivedBytes[i] + "/" + length + " or SHA-256 mismatch");
                }
                Response response = client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.DISCONNECT)
                        .streamId(streamBase + i).port(0).build(), 15000).get(20, TimeUnit.SECONDS);
                if (!response.isSuccess()) throw new AssertionError("DISCONNECT failed");
            }
            System.out.println("CRYPTO_INTEROP_OK suite=" + args[2] + " streams=" + count + " bytes=" + ((long) count * length) + " sha256=matched");
        } catch (Exception | Error failure) {
            failure.printStackTrace();
            throw failure;
        } finally {
            if (client != null) {
                ExchangeClient closing = client;
                Thread cleanup = new Thread(closing::close, "probe-client-cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
                cleanup.join(10000);
                if (cleanup.isAlive()) System.err.println("PROBE_CLEANUP_TIMEOUT: client close exceeded 10 seconds");
            }
            for (Channel capture : captures) capture.close().awaitUninterruptibly(5000);
            if (captureServer != null) captureServer.close().awaitUninterruptibly(5000);
            captureGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(10000);
        }
    }
}
