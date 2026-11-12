package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteImpl.setRegex(String)}, which every regex route reaches
 * through its constructor or {@code pathRegex}. The event is {@code {"regex", router, route,
 * source}}.
 */
public class RegexSetAdvice {
    private static final String MODULE = "vertx-web";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object route, @Advice.FieldValue("router") Object router, @Advice.Argument(0) String regex) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"regex", router, route, regex});
    }
}
