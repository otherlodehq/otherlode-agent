package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code RouterImpl.handle(HttpServerRequest)}. Only a root router
 * receives the server request, since a mounted router is entered through {@code handleContext}.
 * The router itself, not an event array, goes to the module, which declares the routes reachable
 * from it before the request is matched and costs one lookup on the request path once they are.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class RootRequestAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object router) {
        OtherlodeEndpoints.declare(MODULE, router);
    }
}
