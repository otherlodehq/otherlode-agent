package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.vertx.ext.web.Router;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code RouteImpl.subRouter(Router)}, which turns a route into a mount only after it
 * has set the route's handlers, so the route looks like a leaf until then. Entry raises
 * {@code {"mounting", router, route}}, so the walk skips the route meanwhile; exit raises
 * {@code {"mount", router, route, child}}, or {@code {"mountFailed", router, route}} when the call
 * throws. The router is the one the mount route belongs to.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class SubRouterMountedAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object route, @Advice.FieldValue("router") Object router) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"mounting", router, route});
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object route,
            @Advice.FieldValue("router") Object router,
            @Advice.Argument(0) Router child,
            @Advice.Thrown Throwable thrown) {
        if (thrown != null) {
            OtherlodeEndpoints.declare(MODULE, new Object[] {"mountFailed", router, route});
        } else {
            OtherlodeEndpoints.declare(MODULE, new Object[] {"mount", router, route, child});
        }
    }
}
