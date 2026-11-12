package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouterImpl.clear()}, which drops every route and tells nobody.
 * The event is {@code {"clear", router}}.
 */
public class RoutesClearedAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"clear", router});
    }
}
