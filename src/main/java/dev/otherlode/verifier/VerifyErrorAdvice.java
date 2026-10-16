package dev.otherlode.verifier;

import dev.otherlode.bootstrap.OtherlodeVerifierMessages;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of both constructors of {@code java.lang.VerifyError}. It hands the
 * constructor's message argument, null for the no-argument constructor, to {@link
 * OtherlodeVerifierMessages#record}, which stores it without blocking, loading or linking.
 *
 * <p>The argument is read, never {@code getMessage()}, which a subclass can override. This advice
 * is inlined into {@code java.base}, so it may only call the bootstrap seam.
 */
public class VerifyErrorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(value = 0, optional = true) String message) {
        OtherlodeVerifierMessages.record(message);
    }
}
