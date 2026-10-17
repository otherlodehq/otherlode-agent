package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the exit of {@code AbstractUrlHandlerMapping.lookupHandler(PathContainer,
 * ServerWebExchange)}, the dispatch point shared by every URL-mapped handler mapping. A non-null
 * return means a registered pattern matched and its handler passed the mapping's predicate, and
 * Spring has already stored the pattern under {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}.
 * The reactive side has no root handler or default fallback, so every match is a registered
 * pattern and there is no {@code /*} rule. A CORS preflight reaches this method and is no call.
 *
 * <p>The key, {@code List.of(pattern, "*")}, must match {@link RegisterUrlHandlerAdvice}. A miss
 * means a handler this module did not see register, and the dispatch is recorded rather than dropped.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
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
            if (requestMethod != null
                    && "OPTIONS".equals(requestMethod.name())
                    && request.getHeaders().getFirst("Origin") != null
                    && request.getHeaders().getFirst("Access-Control-Request-Method") != null) {
                return;
            }

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
