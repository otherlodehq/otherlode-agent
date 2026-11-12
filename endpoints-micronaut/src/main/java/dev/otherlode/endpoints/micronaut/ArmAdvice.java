package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from the {@code RouteExecutor} constructor and declares the {@code
 * "dispatch-point-constructed"} marker.
 */
public class ArmAdvice {
    private static final String MODULE = "micronaut";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        try {
            OtherlodeEndpoints.declare(MODULE, "dispatch-point-constructed");
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
