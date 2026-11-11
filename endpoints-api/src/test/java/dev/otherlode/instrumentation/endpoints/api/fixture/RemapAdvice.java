package dev.otherlode.instrumentation.endpoints.api.fixture;

import com.example.remap.Before;
import net.bytebuddy.asm.Advice;

public class RemapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Before before) {
    }
}
