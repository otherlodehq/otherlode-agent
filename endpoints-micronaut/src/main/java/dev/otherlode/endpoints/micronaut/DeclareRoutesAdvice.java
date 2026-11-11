package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the {@code DefaultRouter(Collection)} constructor, which every other
 * constructor reaches. It passes the router unopened to {@link OtherlodeEndpoints#declare}, which
 * hands it to {@code MicronautModule.declare}.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class DeclareRoutesAdvice {
    private static final String MODULE = "micronaut";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            OtherlodeEndpoints.declare(MODULE, router);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
