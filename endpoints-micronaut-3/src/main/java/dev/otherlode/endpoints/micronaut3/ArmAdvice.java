package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the constructor of each dispatch point this module counts at: Netty's
 * {@code RoutingInBoundHandler}, micronaut-servlet's {@code ServletHttpHandler} and micronaut-aws's
 * {@code MicronautLambdaContainerHandler}. It declares the {@code "dispatch-point-constructed"}
 * marker, which {@code DispatchGate} reads as the signal to declare the routers it holds.
 */
public class ArmAdvice {
    private static final String MODULE = "micronaut-3";

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
