package io.loglens.agent;

import io.loglens.bootstrap.CaptureBridge;
import net.bytebuddy.asm.Advice;

/** Inlined into Logger; all runtime references are bootstrap-visible JDK-only bridge calls. */
public final class DispatchAdvice {
    private DispatchAdvice() {}
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.This Object logger, @Advice.Argument(0) Object event) {
        return CaptureBridge.enter(logger, event);
    }
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter boolean owner) { CaptureBridge.exit(owner); }
}
