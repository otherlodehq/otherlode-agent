package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to {@code RouteImpl.blockingHandler(Handler, boolean)}, which wraps the
 * handler in a decorator and passes that to {@code handler}. The event is {@code {"blocking",
 * route, handler}} with the handler as written, which the module swaps in for the decorator the
 * next {@link HandlerAddedAdvice} event on the route carries.
 */
public class BlockingHandlerAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object route, @Advice.Argument(0) Object handler) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"blocking", route, handler});
    }
}
