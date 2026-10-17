package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteImpl.setPath(String)} and {@code RouteImpl.method(HttpMethod)},
 * which change what a route declares after it has entered a router. The event is {@code
 * {"changed", router, route}}.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class RouteChangedAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object route, @Advice.FieldValue("router") Object router) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"changed", router, route});
    }
}
