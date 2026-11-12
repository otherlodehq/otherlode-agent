package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to {@code RoutingInBoundHandler.handleRouteMatch}. A {@code UriRouteMatch}
 * counts, unless it is an unregistered WebSocket route. Any other {@code RouteMatch}, such as a
 * {@code StatusRouteMatch}, counts nothing.
 *
 * <p>The advice records a route no registration saw at dispatch, with no handler join. It records
 * an unregistered HEAD route whose method carries the {@code @Get} stereotype against the GET
 * endpoint of the same template.
 */
public class DispatchAdvice {
    private static final String MODULE = "micronaut-3";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) RouteMatch<?> match) {
        try {
            if (!(match instanceof UriRouteMatch) || OtherlodeEndpoints.isDisabled(MODULE)) return;
            UriRoute route = ((UriRouteMatch<?, ?>) match).getRoute();
            Object entry = OtherlodeEndpoints.lookup(MODULE, route);
            if (entry == null) {
                AnnotationMetadata metadata = match.getAnnotationMetadata();
                if (match.isWebSocketRoute() || metadata.hasAnnotation("io.micronaut.websocket.annotation.OnOpen")) return;
                String verb = route.getHttpMethodName();
                if ("HEAD".equals(verb) && metadata.hasStereotype("io.micronaut.http.annotation.Get")) verb = "GET";
                entry = OtherlodeEndpoints.recordDispatch(MODULE, route, verb, route.getUriMatchTemplate().toPathString(), null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
