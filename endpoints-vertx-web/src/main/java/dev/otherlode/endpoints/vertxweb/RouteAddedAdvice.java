package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouterImpl.add(RouteImpl)}, which a route reaches the first time
 * a handler or a failure handler is set on it. The event is {@code {"add", router, route}}.
 */
public class RouteAddedAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router, @Advice.Argument(0) Object route) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"add", router, route});
    }
}
