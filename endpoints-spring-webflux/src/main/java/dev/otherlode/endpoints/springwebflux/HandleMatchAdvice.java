package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Iterator;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the entry of {@code RequestMappingInfoHandlerMapping.handleMatch(RequestMappingInfo,
 * HandlerMethod, ServerWebExchange)}, the dispatch point for annotated controllers. It runs once per
 * matched request, before the handler, with the {@link RequestMappingInfo} narrowed to the patterns
 * and verb that matched. The class also declares a synthetic bridge with the same name and arity,
 * which the module's matcher excludes so a request counts once.
 *
 * <p>The narrowed patterns are a sorted set, best match first, and Spring itself takes {@code
 * iterator().next()}. The verb is the one the methods condition kept, or {@code *} for a mapping
 * with none. A CORS preflight, an {@code OPTIONS} request carrying both {@code Origin} and {@code
 * Access-Control-Request-Method}, reaches this method and is no call.
 *
 * <p>The {@link RequestMappingInfo} here is built per request, so the key is rebuilt by value as
 * {@code List.of(pattern, verb)}, the same two accessors {@link RegisterHandlerMethodAdvice} uses.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class HandleMatchAdvice {
    private static final String MODULE = "spring-webflux";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) RequestMappingInfo info,
            @Advice.Argument(2) ServerWebExchange exchange) {
        try {
            ServerHttpRequest request = exchange.getRequest();
            HttpMethod requestMethod = request.getMethod();
            if (requestMethod != null
                    && "OPTIONS".equals(requestMethod.name())
                    && request.getHeaders().getFirst("Origin") != null
                    && request.getHeaders().getFirst("Access-Control-Request-Method") != null) {
                return;
            }

            Iterator<PathPattern> patterns = info.getPatternsCondition().getPatterns().iterator();
            if (!patterns.hasNext()) return;
            String pattern = patterns.next().getPatternString();

            String verb = "*";
            Iterator<RequestMethod> methods = info.getMethodsCondition().getMethods().iterator();
            if (methods.hasNext()) verb = methods.next().name();

            List<String> key = List.of(pattern, verb);
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, verb, pattern, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
