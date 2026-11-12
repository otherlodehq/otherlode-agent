package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouteExecutor.findRouteMatch(HttpRequest)}. A null return is no match
 * and counts nothing.
 *
 * <p>The advice records a route no registration saw at dispatch, with no handler join. It records
 * a HEAD route whose method carries the {@code @Get} stereotype against the GET endpoint of the
 * same template. A WebSocket route counts nothing.
 */
public class DispatchAdvice {
    private static final String MODULE = "micronaut";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
