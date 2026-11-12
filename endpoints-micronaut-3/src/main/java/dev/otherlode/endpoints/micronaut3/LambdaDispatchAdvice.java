package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.UriRoute;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to micronaut-aws 3.x's {@code MicronautLambdaContainerHandler.handleRequest} that
 * takes the {@code MicronautAwsProxyRequest}, not its synthetic bridge that takes {@code Object}.
 * It counts the route in the request's {@code ROUTE_MATCH} attribute. A request with no route in
 * that attribute counts nothing.
 *
 * <p>Counting follows {@code DispatchAdvice}: the match's {@code UriRoute} is the registered key,
 * the advice records an unseen route without a handler join, and a HEAD route beside a
 * {@code @Get} counts against the GET endpoint.
 */
public class LambdaDispatchAdvice {
    private static final String MODULE = "micronaut-3";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
