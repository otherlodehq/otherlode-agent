package dev.otherlode.endpoints.vertxweb;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteState.matches(RoutingContextImplBase, String, boolean)},
 * which {@code RoutingContextImplBase.iterateNext} calls for each route a request tries and which
 * returns 0 when the route matched. The class is package-private, so the route and the flags are
 * read from its fields. A failure pass and a mount (an exclusive route) count nothing. A request
 * that runs through several routes counts each, and a {@code reroute} counts again.
 *
 * <p>The dispatch key is the route and the chain it was reached through, with the request's verb
 * as a last element when the route lists more than one. The chain is the mount point a static
 * mount gives the router, or a marker for a root router's own use, whose mount point is null. A
 * parameterised mount's mount point holds the matched values, so a lookup that misses with a mount
 * point falls back to the marker for the router's one parameterised chain. {@code RouterWalk}
 * builds the same keys when it declares.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DispatchAdvice {
    private static final String MODULE = "vertx-web";
    private static final String ROOT_CHAIN = "\u0000root";
    private static final String PARAMETERISED_CHAIN = "\u0000parameterised";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Return int result,
            @Advice.Argument(0) RoutingContext context,
            @Advice.Argument(1) String mountPoint,
            @Advice.Argument(2) boolean failure,
            @Advice.FieldValue("route") Object route,
            @Advice.FieldValue("exclusive") boolean exclusive,
            @Advice.FieldValue("methods") Set<?> methods) {
        try {
            if (result != 0 || failure || exclusive) return;
            boolean severalVerbs = methods != null && methods.size() > 1;
            String verb = severalVerbs ? context.request().method().name() : null;
            String chain = mountPoint == null ? ROOT_CHAIN : mountPoint;
            Object entry = OtherlodeEndpoints.lookup(MODULE, severalVerbs ? List.of(route, chain, verb) : List.of(route, chain));
            if (entry == null && mountPoint != null) {
                entry = OtherlodeEndpoints.lookup(
                        MODULE, severalVerbs ? List.of(route, PARAMETERISED_CHAIN, verb) : List.of(route, PARAMETERISED_CHAIN));
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
