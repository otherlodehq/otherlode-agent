package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the {@code RouteExecutor} constructor. A {@code RouteExecutor} is the
 * only route-finding path of Micronaut's request lifecycle. The advice declares the {@code
 * "dispatch-point-constructed"} marker, which {@code DispatchGate} reads as the signal to declare
 * the routers it holds.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class ArmAdvice {
    private static final String MODULE = "micronaut";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        try {
            OtherlodeEndpoints.declare(MODULE, "dispatch-point-constructed");
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
