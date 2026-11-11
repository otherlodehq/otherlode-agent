package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the exit of {@code AbstractUrlHandlerMapping.lookupHandler(PathContainer,
 * ServerWebExchange)}, the dispatch point shared by every URL-mapped handler mapping. A non-null
 * return means a registered pattern matched, and Spring has stored it under {@link
 * HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}. A CORS preflight counts nothing.
 *
 * <p>The key, {@code List.of(pattern, "*")}, matches {@link RegisterUrlHandlerAdvice}. A miss
 * means a handler this module did not see register, and the dispatch is recorded.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class LookupHandlerAdvice {
    private static final String MODULE = "spring-webflux";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Argument(1) ServerWebExchange exchange,
            @Advice.Return Object handler) {
        try {
            if (handler == null) return;
            ServerHttpRequest request = exchange.getRequest();
            HttpMethod requestMethod = request.getMethod();
            if (CorsUtils.isPreFlightRequest(request)) return;

            Object best = exchange.getAttributes().get(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (!(best instanceof PathPattern)) return;
            String pattern = ((PathPattern) best).getPatternString();

            List<String> key = List.of(pattern, "*");
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                String handlerClassName = ClassUtils.getUserClass(handler.getClass()).getName();
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, "*", pattern, null, handlerClassName);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
