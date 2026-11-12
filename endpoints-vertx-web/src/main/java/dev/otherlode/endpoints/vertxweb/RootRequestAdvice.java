package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code RouterImpl.handle(HttpServerRequest)}. Passes the router to
 * {@link OtherlodeEndpoints#declare} for the {@code vertx-web} module.
 */
public class RootRequestAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object router) {
        OtherlodeEndpoints.declare(MODULE, router);
    }
}
