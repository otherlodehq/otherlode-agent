package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code RouteImpl.blockingHandler(Handler, boolean)}, which wraps the
 * handler in a decorator and passes that to {@code handler}. The event is {@code {"blocking",
 * route, handler}} with the handler as written, which the module swaps in for the decorator the
 * next {@link HandlerAddedAdvice} event on the route carries.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class BlockingHandlerAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object route, @Advice.Argument(0) Object handler) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"blocking", route, handler});
    }
}
