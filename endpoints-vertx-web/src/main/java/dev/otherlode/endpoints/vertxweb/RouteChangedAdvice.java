package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouteImpl.setPath(String)} and {@code RouteImpl.method(HttpMethod)},
 * which change what a route declares after it has entered a router. The event is {@code
 * {"changed", router, route}}.
 */
public class RouteChangedAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object route, @Advice.FieldValue("router") Object router) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"changed", router, route});
    }
}
