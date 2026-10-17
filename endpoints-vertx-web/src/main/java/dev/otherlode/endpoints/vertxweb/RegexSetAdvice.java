package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteImpl.setRegex(String)}, which every regex route reaches
 * through its constructor or {@code pathRegex}. The route keeps only the compiled pattern, and
 * on Vert.x Web 5 the agent cannot read the implementation package, so the source is taken here.
 * The event is {@code {"regex", router, route, source}}.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class RegexSetAdvice {
    private static final String MODULE = "vertx-web";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object route, @Advice.FieldValue("router") Object router, @Advice.Argument(0) String regex) {
        OtherlodeEndpoints.declare(MODULE, new Object[] {"regex", router, route, regex});
    }
}
