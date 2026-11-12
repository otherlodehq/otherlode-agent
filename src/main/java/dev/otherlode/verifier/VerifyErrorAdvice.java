package dev.otherlode.verifier;

import dev.otherlode.bootstrap.OtherlodeVerifierMessages;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from both constructors of {@code java.lang.VerifyError}. It hands the
 * constructor's message argument, null for the no-argument constructor, to {@link
 * OtherlodeVerifierMessages#record}. It reads the argument, never {@code getMessage()}, which a
 * subclass can override.
 */
public class VerifyErrorAdvice {
    // Inlined into java.base: it may call only the bootstrap seam.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(value = 0, optional = true) String message) {
        OtherlodeVerifierMessages.record(message);
    }
}
