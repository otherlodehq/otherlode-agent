package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of micronaut-aws 3.x's {@code MicronautLambdaContainerHandler.handleRequest}
 * that takes the {@code MicronautAwsProxyRequest}, not its synthetic bridge that takes {@code Object}
 * and calls it. The handler builds its own {@code RouteExecutor}, so neither Netty's nor
 * micronaut-servlet's dispatch point is reached. {@code MicronautRequestReader} put the route the
 * request matched in the request's {@code ROUTE_MATCH} attribute before this runs; a request that
 * matched none, a CORS preflight with no {@code OPTIONS} route included, carries none.
 *
 * <p>The counting is {@code DispatchAdvice}'s: the match's {@code UriRoute} is the registered key,
 * an unseen route is recorded without a handler join, and a HEAD route beside a {@code @Get}
 * counts against the GET endpoint.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class LambdaDispatchAdvice {
    private static final String MODULE = "micronaut-3";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) HttpRequest<?> request) {
        try {
            if (OtherlodeEndpoints.isDisabled(MODULE)) return;
            Object match = request.getAttribute(HttpAttributes.ROUTE_MATCH).orElse(null);
            if (!(match instanceof UriRouteMatch)) return;
            UriRouteMatch<?, ?> routeMatch = (UriRouteMatch<?, ?>) match;
            UriRoute route = routeMatch.getRoute();
            Object entry = OtherlodeEndpoints.lookup(MODULE, route);
            if (entry == null) {
                AnnotationMetadata metadata = routeMatch.getAnnotationMetadata();
                if (routeMatch.isWebSocketRoute() || metadata.hasAnnotation("io.micronaut.websocket.annotation.OnOpen")) return;
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
