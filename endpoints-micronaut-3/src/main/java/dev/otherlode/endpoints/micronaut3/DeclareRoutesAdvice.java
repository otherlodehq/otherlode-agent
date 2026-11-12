package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from the {@code DefaultRouter(Collection)} constructor and passes the router
 * unopened to {@link OtherlodeEndpoints#declare}.
 */
public class DeclareRoutesAdvice {
    private static final String MODULE = "micronaut-3";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            OtherlodeEndpoints.declare(MODULE, router);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
