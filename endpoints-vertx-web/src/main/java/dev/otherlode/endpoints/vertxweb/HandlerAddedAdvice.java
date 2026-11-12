package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouteImpl.handler(Handler)}, which every context handler reaches,
 * including the one {@code subRouter} sets and the decorator {@code blockingHandler} wraps. The
 * event is {@code {"handler", router, route, handler}}.
 */
public class HandlerAddedAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object route, @Advice.FieldValue("router") Object router, @Advice.Argument(0) Object handler) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"handler", router, route, handler});
    }
}
