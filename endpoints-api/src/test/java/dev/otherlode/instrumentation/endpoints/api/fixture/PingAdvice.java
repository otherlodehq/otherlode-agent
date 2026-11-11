package dev.otherlode.instrumentation.endpoints.api.fixture;

import net.bytebuddy.asm.Advice;

public class PingAdvice {
    public static volatile int entries;

    @Advice.OnMethodEnter
    public static void onEnter() {
        entries++;
    }
}
