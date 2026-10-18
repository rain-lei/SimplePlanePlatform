package io.loglens.agent;

import io.loglens.bootstrap.CaptureBridge;
import net.bytebuddy.asm.Advice;

/** Applied to the inherited doAppend implementation; callback checks actual appender type. */
public final class ConsoleAdvice {
    private ConsoleAdvice() {}
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean enter(@Advice.This Object appender, @Advice.FieldValue("name") String name) {
        return CaptureBridge.skipConsole(appender, name);
    }
}
