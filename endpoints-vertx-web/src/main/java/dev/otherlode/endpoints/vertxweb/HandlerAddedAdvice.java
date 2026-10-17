package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteImpl.handler(Handler)}, which every context handler reaches,
 * including the one {@code subRouter} sets and the decorator {@code blockingHandler} wraps. A
 * route exposes no handlers, so this is how the module learns them. The event is {@code
 * {"handler", router, route, handler}}.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class HandlerAddedAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object route, @Advice.FieldValue("router") Object router, @Advice.Argument(0) Object handler) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"handler", router, route, handler});
    }
}
