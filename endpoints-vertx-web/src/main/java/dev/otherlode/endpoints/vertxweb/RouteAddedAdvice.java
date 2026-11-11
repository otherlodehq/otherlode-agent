package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouterImpl.add(RouteImpl)}, which a route reaches the first time
 * a handler or a failure handler is set on it. The event is {@code {"add", router, route}}.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class RouteAddedAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router, @Advice.Argument(0) Object route) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"add", router, route});
    }
}
