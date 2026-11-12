package dev.otherlode.endpoints.javalin6;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import io.javalin.router.ParsedEndpoint;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to {@code ParsedEndpoint.handle(JavalinServletContext, String)}. It counts an
 * instance this module registered, and counts one it did not register only when its method
 * {@code isHttpMethod()}.
 */
public class DispatchAdvice {
    private static final String MODULE = "javalin-6";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
