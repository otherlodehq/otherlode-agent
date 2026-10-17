package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouteExecutor.findRouteMatch(HttpRequest)}, which Micronaut's
 * request lifecycle calls once per request after the pre-matching filters and before argument
 * binding. A null return is no match and counts nothing.
 *
 * <p>The route's {@code UriRouteInfo} is the key {@code MicronautModule.declare} registered it
 * under. A route no registration saw is recorded at dispatch, with no handler join. A HEAD route
 * whose method carries the {@code @Get} stereotype is the one Micronaut adds beside a {@code @Get},
 * so it is recorded against the GET endpoint of the same template. A WebSocket route, which
 * {@code isWebSocketRoute()} reports only for an {@code @OnMessage} method, is counted by neither.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DispatchAdvice {
    private static final String MODULE = "micronaut";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return UriRouteMatch<?, ?> returned) {
        try {
            if (returned == null || OtherlodeEndpoints.isDisabled(MODULE)) return;
            UriRouteInfo<?, ?> info = returned.getRouteInfo();
            Object entry = OtherlodeEndpoints.lookup(MODULE, info);
            if (entry == null) {
                AnnotationMetadata metadata = info.getAnnotationMetadata();
                if (info.isWebSocketRoute() || metadata.hasAnnotation("io.micronaut.websocket.annotation.OnOpen")) return;
                String verb = info.getHttpMethodName();
                if ("HEAD".equals(verb) && metadata.hasStereotype("io.micronaut.http.annotation.Get")) verb = "GET";
                entry = OtherlodeEndpoints.recordDispatch(MODULE, info, verb, info.getUriMatchTemplate().toPathString(), null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
