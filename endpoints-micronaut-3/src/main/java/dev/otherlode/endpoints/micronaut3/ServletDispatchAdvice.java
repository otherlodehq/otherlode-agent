package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of micronaut-servlet's {@code ServletHttpHandler.invokeRouteMatch}, which
 * the handler calls once per request with the route it chose, before the filters and the handler.
 * A status route reaches it too, as a {@code StatusRouteMatch}, and counts nothing.
 *
 * <p>From micronaut-servlet 3.3 the handler also dispatches a CORS preflight to the first route
 * that matches the path under any verb, usually not an {@code OPTIONS} route, whereas a route the
 * handler chose by the request's own verb always agrees with it, so a route counts only when the
 * two agree.
 *
 * <p>The counting is {@code DispatchAdvice}'s: the match's {@code UriRoute} is the registered key,
 * an unseen route is recorded without a handler join, and a HEAD route beside a {@code @Get}
 * counts against the GET endpoint.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ServletDispatchAdvice {
    private static final String MODULE = "micronaut-3";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) HttpRequest<?> request, @Advice.Argument(2) RouteMatch<?> match) {
        try {
            if (!(match instanceof UriRouteMatch) || OtherlodeEndpoints.isDisabled(MODULE)) return;
            UriRoute route = ((UriRouteMatch<?, ?>) match).getRoute();
            String verb = route.getHttpMethodName();
            if (!verb.equals(request.getMethodName())) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, route);
            if (entry == null) {
                AnnotationMetadata metadata = match.getAnnotationMetadata();
                if (match.isWebSocketRoute() || metadata.hasAnnotation("io.micronaut.websocket.annotation.OnOpen")) return;
                if ("HEAD".equals(verb) && metadata.hasStereotype("io.micronaut.http.annotation.Get")) verb = "GET";
                entry = OtherlodeEndpoints.recordDispatch(MODULE, route, verb, route.getUriMatchTemplate().toPathString(), null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
