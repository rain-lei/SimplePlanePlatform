package com.proxy.remote.integration;

import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.exchange.header.ExchangeHandler;
import com.proxy.exchange.header.HeaderExchanger;
import com.proxy.remote.dispatch.DispatchInvoker;
import com.proxy.remote.outbound.OutboundConnector;
import com.proxy.transport.http3.Http3Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the real HTTP/3 remote outbound path, not just a mock ExchangeHandler. */
class Http3OutboundIntegrationTest {
    /** Opt-in diagnosis: exercise stream credit recycling on one real QUIC connection. */
    @ParameterizedTest
    @ValueSource(strings = {"disconnect", "transport-close", "target-close", "connect-rejected", "connect-timeout",
            "disconnect-peer-close", "transport-reset", "transport-close-guard", "healthy-idle", "cancel-opening"})
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "http3.lifecycle.probe", matches = "true")
    void streamCreditsMustBeRecycledWithSequentialConnections(String mode) throws Exception {
        String selectedMode = System.getProperty("http3.lifecycle.mode", "all");
        org.junit.jupiter.api.Assumptions.assumeTrue("all".equals(selectedMode)
                || java.util.Arrays.asList(selectedMode.split(",")).contains(mode));
        int limit = Integer.getInteger("http3.lifecycle.limit", 32);
        int cycles = Integer.getInteger("http3.lifecycle.cycles", limit * 5);
        String suite = System.getProperty("http3.lifecycle.cipher", "none");
        TestCertificates certificates = TestCertificates.create();
        EventLoopGroup workers = new NioEventLoopGroup(1);
        io.netty.channel.group.ChannelGroup targets = new io.netty.channel.group.DefaultChannelGroup(
                io.netty.util.concurrent.GlobalEventExecutor.INSTANCE);
        java.util.Set<Channel> serverStreams = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger targetActive = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger targetPeak = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger backendConnects = new java.util.concurrent.atomic.AtomicInteger();
        Channel target = null;
        Http3Server server = null;
        ExchangeClient client = null;
        DispatchInvoker dispatch = null;
        int completed = 0;
        Throwable stopped = null;
        try {
            target = new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            targets.add(channel);
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    targetPeak.accumulateAndGet(targetActive.incrementAndGet(), Math::max);
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object message) {
                                    io.netty.util.ReferenceCountUtil.release(message);
                                    if ("target-close".equals(mode)) ctx.close();
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                    targetActive.decrementAndGet();
                                }
                            });
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) target.localAddress()).getPort();
            if ("connect-rejected".equals(mode)) {
                target.close().sync();
            }
            dispatch = new DispatchInvoker(java.util.concurrent.Executors.newFixedThreadPool(2),
                    new OutboundConnector(workers, 500), 500);
            final DispatchInvoker actualDispatch = dispatch;
            com.proxy.common.filter.Invoker backend = invocation -> {
                if (invocation.getType() == ProxyMessage.MessageType.CONNECT) backendConnects.incrementAndGet();
                if ("connect-timeout".equals(mode)
                        && invocation.getType() == ProxyMessage.MessageType.CONNECT) {
                    return new CompletableFuture<>();
                }
                return actualDispatch.invoke(invocation);
            };
            int port;
            try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
            URL serverUrl = new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3").addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.maxStreamsPerConnection", String.valueOf(limit))
                    .addParameter("http3.streamCleanupIntervalMs", "100")
                    .addParameter("http3.certificateFile", certificates.certificate.getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificates.privateKey.getAbsolutePath());
            server = new Http3Server(serverUrl, new ExchangeHandler(backend) {
                @Override
                public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                    if ("transport-close-guard".equals(mode)) {
                        ctx.pipeline().addBefore("h3-stream", "drop-fin-event", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
                                if (!(event instanceof io.netty.channel.socket.ChannelInputShutdownEvent)) {
                                    super.userEventTriggered(context, event);
                                }
                            }
                        });
                    }
                    serverStreams.add(ctx.channel());
                    ctx.channel().closeFuture().addListener(f -> serverStreams.remove(ctx.channel()));
                    super.handlerAdded(ctx);
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
                    if ("disconnect-peer-close".equals(mode)
                            && event instanceof io.netty.channel.socket.ChannelInputShutdownEvent) {
                        ctx.close();
                    }
                    super.userEventTriggered(ctx, event);
                }
            });
            server.start();
            client = new HeaderExchanger().connect(new URL("proxy", "localhost", port)
                    .addParameter("transport", "http3").addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile", certificates.ca.getAbsolutePath()));
            for (int index = 0; index < cycles; index++) {
                long id = 100_000L + index;
                try {
                    Response response = null;
                    try {
                        CompletableFuture<Response> connecting = client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.CONNECT)
                                .streamId(id).host("127.0.0.1").port(targetPort).build(),
                                "connect-timeout".equals(mode) ? 100 : 1500);
                        if ("cancel-opening".equals(mode)) client.getClient().closeStream(id);
                        response = connecting.get(3, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException expectedTimeout) {
                        if (!"connect-timeout".equals(mode) && !"cancel-opening".equals(mode)) throw expectedTimeout;
                    }
                    if (!"cancel-opening".equals(mode)) {
                        org.junit.jupiter.api.Assertions.assertTrue(backendConnects.get() > index,
                                "CONNECT never reached server; local timeout must not count as a successful cycle");
                    }
                    if (!"connect-timeout".equals(mode) && !"cancel-opening".equals(mode)) {
                        org.junit.jupiter.api.Assertions.assertNotNull(response);
                        org.junit.jupiter.api.Assertions.assertEquals(!"connect-rejected".equals(mode),
                                response.isSuccess());
                    }
                    if ("target-close".equals(mode)) {
                        client.send(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(id)
                                .host("127.0.0.1").port(targetPort).data(new byte[]{1}).build());
                    } else if ("healthy-idle".equals(mode)) {
                        Thread.sleep(350);
                        org.junit.jupiter.api.Assertions.assertEquals(1, client.getClient().getActiveStreamCount(),
                                "Cleanup must preserve idle streams that have not ended either direction");
                        client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.DISCONNECT)
                                .streamId(id).host("127.0.0.1").port(targetPort).build(), 1500)
                                .get(3, TimeUnit.SECONDS);
                    } else if ("disconnect".equals(mode) || "disconnect-peer-close".equals(mode)) {
                        client.request(ProxyMessage.builder().type(ProxyMessage.MessageType.DISCONNECT)
                                .streamId(id).host("127.0.0.1").port(targetPort).build(), 1500)
                                .get(3, TimeUnit.SECONDS);
                    } else if ("transport-close".equals(mode)) {
                        client.getClient().closeStream(id);
                    } else if ("transport-reset".equals(mode) || "transport-close-guard".equals(mode)) {
                        java.lang.reflect.Field statesField = client.getClient().getClass().getDeclaredField("streams");
                        statesField.setAccessible(true);
                        Object state = ((java.util.Map<?, ?>) statesField.get(client.getClient())).get(id);
                        java.lang.reflect.Field channelField = state.getClass().getDeclaredField("channel");
                        channelField.setAccessible(true);
                        io.netty.handler.codec.quic.QuicStreamChannel stream =
                                (io.netty.handler.codec.quic.QuicStreamChannel) channelField.get(state);
                        if ("transport-close-guard".equals(mode)) {
                            // Drop server FIN notification above: only its wheel can close output.
                            stream.shutdownOutput().sync();
                        } else {
                            stream.shutdown(0).sync();
                            stream.close().sync();
                        }
                    }
                    // Allow close/MAX_STREAMS frames to reach the peer; do not build a burst.
                    Thread.sleep(10);
                    long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!serverStreams.isEmpty() && System.nanoTime() < closeDeadline) Thread.sleep(5);
                    org.junit.jupiter.api.Assertions.assertTrue(serverStreams.isEmpty(),
                            "Server stream did not close after a terminal event");
                    completed++;
                } catch (Throwable failure) {
                    stopped = failure;
                    break;
                }
            }
            // Optional observation across the existing ten-second server cleanup scan.
            Thread.sleep(Long.getLong("http3.lifecycle.settleMs", 0L));
            java.lang.reflect.Field sessionsField = DispatchInvoker.class.getDeclaredField("sessionManager");
            sessionsField.setAccessible(true);
            com.proxy.remote.outbound.SessionManager sessions =
                    (com.proxy.remote.outbound.SessionManager) sessionsField.get(dispatch);
            java.lang.reflect.Field connectionField = client.getClient().getClass().getDeclaredField("connection");
            connectionField.setAccessible(true);
            io.netty.handler.codec.quic.QuicChannel quic =
                    ((com.proxy.transport.http3.Http3Connection) connectionField.get(client.getClient())).channel();
            long credit = quic.eventLoop().submit(() -> quic.peerAllowedStreams(
                    io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL)).get(3, TimeUnit.SECONDS);
            System.out.printf("LIFECYCLE mode=%s cipher=%s limit=%d planned=%d completed=%d clientStreams=%d "
                            + "serverStreams=%d serverSessions=%d targetActive=%d targetPeak=%d "
                            + "backendConnects=%d peerCredit=%d stopped=%s%n",
                    mode, suite, limit, cycles, completed, client.getClient().getActiveStreamCount(),
                    serverStreams.size(), sessions.activeCount(), targetActive.get(), targetPeak.get(),
                    backendConnects.get(), credit,
                    stopped == null ? "none" : stopped.toString());
            org.junit.jupiter.api.Assertions.assertEquals(cycles, completed,
                    "Sequential connections exhausted QUIC stream credit: " + mode);
            org.junit.jupiter.api.Assertions.assertNull(stopped, "Lifecycle stopped: " + mode);
            org.junit.jupiter.api.Assertions.assertEquals(0, client.getClient().getActiveStreamCount());
            org.junit.jupiter.api.Assertions.assertEquals(0, serverStreams.size());
            org.junit.jupiter.api.Assertions.assertEquals(0, sessions.activeCount());
            long targetDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (targetActive.get() != 0 && System.nanoTime() < targetDeadline) Thread.sleep(5);
            org.junit.jupiter.api.Assertions.assertEquals(0, targetActive.get());
        } finally {
            if (client != null) client.close();
            if (server != null) server.close();
            if (dispatch != null) dispatch.shutdown();
            if (target != null) target.close();
            targets.close().awaitUninterruptibly();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
            certificates.delete();
        }
    }

    @Test
    void normalDisconnectRecyclesCreditBeyondInitialLimit() throws Exception {
        streamCreditsMustBeRecycledWithSequentialConnections("disconnect");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "chacha20-poly1305-v2", "aes-gcm-v2"})
    void forwardsDataToOutboundTcpTargetAndPushesResponseBackAfterConnect(String suite) throws Exception {
        TestCertificates certificates = TestCertificates.create();
        EventLoopGroup targetBoss = new NioEventLoopGroup(1);
        EventLoopGroup targetWorkers = new NioEventLoopGroup(1);
        EventLoopGroup outboundWorkers = new NioEventLoopGroup(1);
        Channel targetServer = null;
        Http3Server proxyServer = null;
        ExchangeClient proxyClient = null;
        DispatchInvoker dispatch = null;
        try {
            ServerBootstrap targetBootstrap = new ServerBootstrap()
                    .group(targetBoss, targetWorkers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                private int received;
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                                    // Force the response through OutboundSession.writeBack
                                    // and close immediately after the last echo: EOF must not
                                    // truncate the final DATA record on the QUIC stream.
                                    received += message.readableBytes();
                                    io.netty.channel.ChannelFuture write = ctx.writeAndFlush(message.retain());
                                    if (received == 2 * 1024 * 1024 + 1) {
                                        write.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                                    }
                                }
                            });
                        }
                    });
            targetServer = targetBootstrap.bind("127.0.0.1", 0).sync().channel();
            int targetPort = ((java.net.InetSocketAddress) targetServer.localAddress()).getPort();

            OutboundConnector connector = new OutboundConnector(outboundWorkers, 3000);
            dispatch = new DispatchInvoker(
                    java.util.concurrent.Executors.newFixedThreadPool(2), connector, 3000);

            int http3Port;
            try (DatagramSocket socket = new DatagramSocket(0)) {
                http3Port = socket.getLocalPort();
            }
            URL serverUrl = new URL("proxy", "localhost", http3Port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.certificateFile", certificates.certificate.getAbsolutePath())
                    .addParameter("http3.privateKeyFile", certificates.privateKey.getAbsolutePath());
            proxyServer = new Http3Server(serverUrl, new ExchangeHandler(dispatch));
            proxyServer.start();

            ExtensionLoader.resetAll();
            URL clientUrl = new URL("proxy", "localhost", http3Port)
                    .addParameter("transport", "http3")
                    .addParameter("cipher", suite)
                    .addParameter("cipherKey", "0123456789abcdef0123456789abcdef")
                    .addParameter("http3.caFile", certificates.ca.getAbsolutePath());
            proxyClient = new HeaderExchanger().connect(clientUrl);
            java.util.concurrent.ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry =
                    new java.util.concurrent.ConcurrentHashMap<>();
            proxyClient.setStreamRegistry(streamRegistry);

            long streamId = 901;
            Response connect = proxyClient.request(ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.CONNECT)
                    .host("127.0.0.1")
                    .port(targetPort)
                    .streamId(streamId)
                    .build(), 5000).get(10, TimeUnit.SECONDS);
            assertTrue(connect.isSuccess(), "CONNECT should establish outbound TCP session");

            byte[] payload = new byte[2 * 1024 * 1024 + 1];
            new java.util.Random(8439).nextBytes(payload);
            BlockingQueue<byte[]> pushQueue = new LinkedBlockingQueue<>();
            EmbeddedChannel pushCapture = createPushCapture(streamId, pushQueue, streamRegistry);
            proxyClient.send(ProxyMessage.builder()
                    .type(ProxyMessage.MessageType.DATA)
                    .host("127.0.0.1")
                    .port(targetPort)
                    .streamId(streamId)
                    .data(payload)
                    .build());

            java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (received.size() < payload.length && System.nanoTime() < deadline) {
                pushCapture.runPendingTasks();
                byte[] part = pushQueue.poll(100, TimeUnit.MILLISECONDS);
                if (part != null) received.write(part);
            }
            assertArrayEquals(payload, received.toByteArray(),
                    "outbound TCP response should be pushed back over HTTP/3");
            pushCapture.finishAndReleaseAll();
        } finally {
            if (proxyClient != null) proxyClient.close();
            if (proxyServer != null) proxyServer.close();
            if (dispatch != null) dispatch.shutdown();
            if (targetServer != null) targetServer.close();
            outboundWorkers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            targetBoss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            targetWorkers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            certificates.delete();
        }
    }

    private EmbeddedChannel createPushCapture(long streamId, BlockingQueue<byte[]> pushQueue,
                                              java.util.concurrent.ConcurrentHashMap<Long, ChannelHandlerContext> streamRegistry) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) msg;
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    buf.release();
                    pushQueue.offer(data);
                    promise.setSuccess();
                } else {
                    ctx.write(msg, promise);
                }
            }
        }, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
            }
        });
        streamRegistry.put(streamId, channel.pipeline().lastContext());
        return channel;
    }

    private static final class TestCertificates {
        private final Path directory;
        private final java.io.File certificate;
        private final java.io.File privateKey;
        private final java.io.File ca;

        private TestCertificates(Path directory, java.io.File certificate,
                                 java.io.File privateKey, java.io.File ca) {
            this.directory = directory;
            this.certificate = certificate;
            this.privateKey = privateKey;
            this.ca = ca;
        }

        static TestCertificates create() throws Exception {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            Path directory = Files.createTempDirectory("http3-remote-test-tls");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair caKey = generator.generateKeyPair();
            KeyPair serverKey = generator.generateKeyPair();
            Date notBefore = new Date(System.currentTimeMillis() - 60_000);
            Date notAfter = new Date(System.currentTimeMillis() + 86_400_000);
            X500Name caName = new X500Name("CN=SimplePlane Test CA");
            X500Name serverName = new X500Name("CN=localhost");
            X509Certificate caCert = certificate(caName, caName, caKey, caKey,
                    notBefore, notAfter, true);
            X509Certificate serverCert = certificate(caName, serverName, caKey, serverKey,
                    notBefore, notAfter, false);
            java.io.File certificate = directory.resolve("server-chain.pem").toFile();
            java.io.File privateKey = directory.resolve("server-key.pem").toFile();
            java.io.File ca = directory.resolve("ca.pem").toFile();
            writePem(certificate, serverCert, caCert);
            writePem(privateKey, serverKey.getPrivate());
            writePem(ca, caCert);
            return new TestCertificates(directory, certificate, privateKey, ca);
        }

        private static X509Certificate certificate(X500Name issuer, X500Name subject,
                                                   KeyPair issuerKey, KeyPair subjectKey,
                                                   Date notBefore, Date notAfter, boolean ca) throws Exception {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuer, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter,
                    subject, subjectKey.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            builder.addExtension(Extension.keyUsage, false,
                    new KeyUsage(ca ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                            : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            if (!ca) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
            }
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(issuerKey.getPrivate());
            return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
        }

        private static void writePem(java.io.File file, Object... values) throws IOException {
            try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.US_ASCII);
                 JcaPEMWriter pem = new JcaPEMWriter(writer)) {
                for (Object value : values) pem.writeObject(value);
            }
        }

        private void delete() throws IOException {
            Files.deleteIfExists(certificate.toPath());
            Files.deleteIfExists(privateKey.toPath());
            Files.deleteIfExists(ca.toPath());
            Files.deleteIfExists(directory);
        }
    }
}
