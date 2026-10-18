package io.loglens.agent;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Premain installation, preserving original Logger dispatch and non-console appender branches. */
public final class LogbackTransformer {
    private LogbackTransformer() {}
    public static ResettableClassFileTransformer install(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .disableClassFormatChanges()
                .type(named("ch.qos.logback.classic.Logger"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(DispatchAdvice.class)
                        .on(named("callAppenders").and(takesArguments(1)))))
                .type(named("ch.qos.logback.core.UnsynchronizedAppenderBase").or(named("ch.qos.logback.core.AppenderBase")))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(ConsoleAdvice.class)
                        .on(named("doAppend").and(takesArguments(1)))))
                .installOn(instrumentation);
    }
}
