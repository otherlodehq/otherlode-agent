package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouterImpl.clear()}, which drops every route and tells nobody.
 * The event is {@code {"clear", router}}.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class RoutesClearedAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"clear", router});
    }
}
