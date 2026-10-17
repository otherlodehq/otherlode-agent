package dev.otherlode.endpoints.javalin6;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.router.ParsedEndpoint;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code ParsedEndpoint.handle(JavalinServletContext, String)}. Javalin's
 * HTTP lifecycle task calls it once per request, for the first endpoint that matched the method
 * and path, after the before-handlers ran and only if none of them rejected, redirected or
 * skipped the request. The before, after, beforeMatched and afterMatched tasks call it on their
 * own endpoints too, and those are no call: an instance this module registered is an HTTP
 * endpoint, and one it did not register is counted only when its method {@code isHttpMethod()}.
 * A HEAD request with no HEAD route and a GET route runs no endpoint, so it counts nothing.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DispatchAdvice {
    private static final String MODULE = "javalin-6";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This ParsedEndpoint parsed) {
        try {
            if (OtherlodeEndpoints.isDisabled(MODULE)) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, parsed);
            if (entry == null) {
                Endpoint endpoint = parsed.getEndpoint();
                HandlerType type = endpoint.getMethod();
                if (!type.isHttpMethod()) return;
                entry = OtherlodeEndpoints.recordDispatch(MODULE, parsed, type.name(), endpoint.getPath(), null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
