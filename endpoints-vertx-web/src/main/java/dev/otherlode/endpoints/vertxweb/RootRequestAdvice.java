package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code RouterImpl.handle(HttpServerRequest)}. Only a root router
 * receives the server request, since a mounted router is entered through {@code handleContext}.
 * The router itself, not an event array, goes to the module, which declares the routes reachable
 * from it.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class RootRequestAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object router) {
        OtherlodeEndpoints.declare(MODULE, router);
    }
}
