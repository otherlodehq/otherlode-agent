package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the constructor of each dispatch point this module counts at: Netty's
 * {@code RoutingInBoundHandler}, micronaut-servlet's {@code ServletHttpHandler} and micronaut-aws's
 * {@code MicronautLambdaContainerHandler}. Once one exists, the routes of a router are countable.
 * The module receives the {@code "dispatch-point-constructed"} marker, which its {@code DispatchGate}
 * reads as the signal to declare the routers it holds.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ArmAdvice {
    private static final String MODULE = "micronaut-3";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
        try {
            OtherlodeEndpoints.declare(MODULE, "dispatch-point-constructed");
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
