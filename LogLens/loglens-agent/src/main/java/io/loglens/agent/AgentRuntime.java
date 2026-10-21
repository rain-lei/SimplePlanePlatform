package io.loglens.agent;

import io.loglens.adapter.logback.LogbackCapture;
import io.loglens.api.LogClock;
import io.loglens.bootstrap.CaptureBridge;
import io.loglens.config.ConfigLoader;
import io.loglens.proxyremote.ProxyRemoteMonitor;
import io.loglens.proxyremote.ProxyRemoteTransformer;
import io.loglens.runtime.ProcessingRuntime;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry invoked reflectively from the minimal premain outer JAR in an isolated loader. */
public final class AgentRuntime implements AutoCloseable {
    public static final class StrictInitializationFailure extends Exception {
        StrictInitializationFailure(Throwable cause) { super("Strict Agent initialization failed", cause); }
    }
    private final ProcessingRuntime runtime;
    private final Instrumentation instrumentation;
    private final List<ResettableClassFileTransformer> transformers;
    private final ProxyRemoteMonitor proxyMonitor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private AgentRuntime(ProcessingRuntime runtime, Instrumentation instrumentation,
                         List<ResettableClassFileTransformer> transformers, ProxyRemoteMonitor proxyMonitor) {
        this.runtime=runtime; this.instrumentation=instrumentation; this.transformers=List.copyOf(transformers); this.proxyMonitor=proxyMonitor;
    }
    public static AgentRuntime start(String configPath, Instrumentation instrumentation) throws Exception {
        var config=ConfigLoader.load(Path.of(configPath)); LogClock clock=LogClock.system();
        ProcessingRuntime runtime;
        try { runtime=new ProcessingRuntime(config,clock); }
        catch(Exception failure){if(config.agent().failurePolicy()==io.loglens.config.LogLensConfig.FailurePolicy.STRICT)throw new StrictInitializationFailure(failure);throw failure;}
        List<ResettableClassFileTransformer> transformers=new ArrayList<>(); ProxyRemoteMonitor monitor=null;
        try {
            transformers.add(LogbackTransformer.install(instrumentation));
            CaptureBridge.install(new LogbackCapture(runtime,clock),config.agent().consoleAppenderNames());
            if(config.proxyRemote().enabled()) {
                monitor=new ProxyRemoteMonitor(runtime.storage().sessionId(),config.proxyRemote().sampleIntervalMillis(),
                        config.proxyRemote().maxTrackedConnections(),config.proxyRemote().maxTrackedSessions(),runtime::publishTelemetry);
                CaptureBridge.installTelemetry(monitor);
                transformers.add(ProxyRemoteTransformer.install(instrumentation));
                runtime.activeTelemetry(monitor::activeSnapshot);
            }
            AgentRuntime session=new AgentRuntime(runtime,instrumentation,transformers,monitor);
            Thread hook=new Thread(()->{try{session.close();}catch(Exception failure){System.err.println("[LogLens] shutdown failed: "+failure.getClass().getName());}},"loglens-shutdown");
            hook.setContextClassLoader(AgentRuntime.class.getClassLoader()); Runtime.getRuntime().addShutdownHook(hook);
            runtime.storage().report("AGENT_ACTIVE","mode="+config.agent().mode()+" webPort="+runtime.webPort()+" proxyRemoteTelemetry="+config.proxyRemote().enabled()+"");
            return session;
        } catch(Throwable failure) {
            CaptureBridge.disable(); if(monitor!=null)try{monitor.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            for(ResettableClassFileTransformer transformer:transformers)try{instrumentation.removeTransformer(transformer);}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            try{runtime.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            if(config.agent().failurePolicy()==io.loglens.config.LogLensConfig.FailurePolicy.STRICT)throw new StrictInitializationFailure(failure); throw failure;
        }
    }
    @Override public void close() throws Exception {
        if(!closed.compareAndSet(false,true))return;
        CaptureBridge.disable();
        for(ResettableClassFileTransformer transformer:transformers)instrumentation.removeTransformer(transformer);
        if(proxyMonitor!=null)proxyMonitor.close(); runtime.close();
    }
}
