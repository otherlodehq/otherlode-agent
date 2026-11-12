package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Iterator;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Runs on entry to {@code RequestMappingInfoHandlerMapping.handleMatch(RequestMappingInfo,
 * HandlerMethod, ServerWebExchange)}, the dispatch point for annotated controllers. It runs once per
 * matched request, before the handler, with the {@link RequestMappingInfo} narrowed to the patterns
 * and verb that matched.
 *
 * <p>The pattern is the first of the narrowed patterns, which Spring sorts best match first. The verb
 * is the one the methods condition kept, or {@code *} for a mapping with none. A CORS preflight
 * counts nothing.
 */
public class HandleMatchAdvice {
    private static final String MODULE = "spring-webflux";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) RequestMappingInfo info,
            @Advice.Argument(2) ServerWebExchange exchange) {
        try {
            ServerHttpRequest request = exchange.getRequest();
            HttpMethod requestMethod = request.getMethod();
            if (CorsUtils.isPreFlightRequest(request)) return;

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
