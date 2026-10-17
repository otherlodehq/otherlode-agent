package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of the Netty server's {@code RoutingInBoundHandler.handleRouteMatch}, which
 * runs once per request that matched a route, before the filters and the handler. A status route
 * reaches it too, as a {@code StatusRouteMatch}, and counts nothing; a {@code UriRouteMatch}
 * counts.
 *
 * <p>The match's {@code UriRoute} is the key {@code Micronaut3Module.declare} registered it under.
 * A route no registration saw is recorded at dispatch, with no handler join. A HEAD route whose
 * method carries the {@code @Get} stereotype is the one Micronaut adds beside a {@code @Get}, so it
 * is recorded against the GET endpoint of the same template.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DispatchAdvice {
    private static final String MODULE = "micronaut-3";

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
