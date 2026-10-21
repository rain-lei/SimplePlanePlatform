package io.loglens.proxyremote;

import io.loglens.bootstrap.CaptureBridge;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import java.lang.instrument.Instrumentation;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Runtime-only instrumentation for SimplePlane proxy-remote; it has no compile-time dependency on that app. */
public final class ProxyRemoteTransformer {
    private ProxyRemoteTransformer() {}
    public static ResettableClassFileTransformer install(Instrumentation instrumentation) {
        return new AgentBuilder.Default().with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable failure) {
                        System.err.println("[LogLens] ProxyRemote transformation failed for " + typeName + ": " + failure.getClass().getName());
                    }
                }).disableClassFormatChanges()
                .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("io.loglens.")))
                .type(named("com.proxy.remote.outbound.SessionManager"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(SessionRegisterAdvice.class).on(named("register").and(takesArguments(2)))) )
                .type(named("com.proxy.remote.outbound.OutboundSession"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(SessionCloseAdvice.class).on(named("close").and(takesArguments(0))))
                        .visit(Advice.to(SessionActiveAdvice.class).on(named("setOutboundChannel").and(takesArguments(1))))
                        .visit(Advice.to(UplinkAdvice.class).on(named("forward").and(takesArguments(byte[].class))))
                        .visit(Advice.to(DownlinkAdvice.class).on(named("writeBack").and(takesArguments(byte[].class)))))
                .type(nameStartsWith("com.proxy.transport.netty.NettyServer$")
                        .or(nameStartsWith("com.proxy.transport.http3.Http3Server$")))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(ConnectionInitAdvice.class).on(named("initChannel").and(takesArguments(1)))))
                .installOn(instrumentation);
    }
    public static final class ConnectionInitAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) Object channel, @Advice.Origin("#t") String type) {
            CaptureBridge.connectionOpened(channel, type.contains("Http3Server") ? "http3" : "http2");
        }
    }
    public static final class SessionRegisterAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(1) Object session) { CaptureBridge.sessionRegistered(session); }
    }
    public static final class SessionActiveAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object session) { CaptureBridge.sessionActive(session); }
    }
    public static final class SessionCloseAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object session) { CaptureBridge.sessionClosed(session); }
    }
    public static final class UplinkAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.This Object session, @Advice.Argument(0) byte[] data, @Advice.Thrown Throwable failure) {
            if (failure == null) CaptureBridge.uplink(session, data == null ? 0 : data.length);
        }
    }
    public static final class DownlinkAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.This Object session, @Advice.Argument(0) byte[] data, @Advice.Thrown Throwable failure) {
            if (failure == null) CaptureBridge.downlink(session, data == null ? 0 : data.length);
        }
    }
}
