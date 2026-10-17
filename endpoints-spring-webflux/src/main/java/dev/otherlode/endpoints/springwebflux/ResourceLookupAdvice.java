package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/**
 * Woven onto the exit of {@code PathResourceLookupFunction.apply(ServerRequest)}, the lookup
 * function behind {@code RouterFunctions.resources(pattern, location)}. The reactive lookup returns
 * a {@code Mono<Resource>}, so a resource is found only when the Mono emits. The advice replaces the
 * returned Mono with {@code mono.doOnNext(consumer)}, where {@link
 * OtherlodeEndpoints#resourceEmitted} builds the consumer, since advice cannot define a class or a
 * lambda. {@code doOnNext} passes every signal, the subscription and its demand through
 * unchanged, so the woven method behaves as the unwoven one apart from counting; the consumer
 * never throws.
 *
 * <p>On each emitted resource the consumer marks the request's exchange, so {@code
 * RouterFunctionMapping.setAttributes}, whose own {@code doOnNext} runs next on the same thread for
 * the same request, does not count it under the nest's path. The mark is keyed by the exchange:
 * a nested request has an attribute map of its own, and the exchange is the one object this advice
 * and {@link SetAttributesAdvice} both reach, through {@code ServerRequest.exchange()}. The consumer
 * then counts the endpoint, unless the request is a preflight or has a verb the resource handler
 * refuses, which are no call.
 *
 * <p>The key, nest pattern, verb and preflight flag are computed here, synchronously, since the
 * attributes of a nested request are not stable once the Mono is returned. The endpoint is looked
 * up here, so the template is combined only for a route the walk did not declare. It is recorded as
 * discovered when the Mono emits, not here: a lookup that finds nothing is a miss and must create
 * no endpoint.
 *
 * <p>The key, {@code List.of("fnres", lookupFunction, nestPattern, verb)}, must match the walk's.
 * The registry holds the lookup function weakly.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class ResourceLookupAdvice {
    private static final String MODULE = "spring-webflux";
    private static final String RESOURCE_KEY = "fnres";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object lookupFunction,
            @Advice.FieldValue("pattern") PathPattern pattern,
            @Advice.Argument(0) ServerRequest request,
            @Advice.Return(readOnly = false) Mono<Resource> result) {
        try {
            if (result == null || result == Mono.<Resource>empty() || OtherlodeEndpoints.isDisabled(MODULE)) return;
            Object exchange;
            try {
                exchange = request.exchange();
            } catch (RuntimeException e) {
                // A ServerRequest built without an exchange, such as spring-test's MockServerRequest.
                exchange = null;
            }
            if (exchange == null) return;

            HttpMethod httpMethod = request.method();
            String verb = httpMethod == null ? null : httpMethod.name();
            boolean counted =
                    verb != null
                            && ("GET".equals(verb) || "HEAD".equals(verb) || "OPTIONS".equals(verb))
                            && !("OPTIONS".equals(verb)
                                    && request.headers().asHttpHeaders().getFirst("Origin") != null
                                    && request.headers().asHttpHeaders().getFirst("Access-Control-Request-Method") != null);

            Object key = null;
            Object entry = null;
            String template = null;
            if (counted) {
                Object nest = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
                String nestPattern = "";
                if (nest instanceof PathPattern) {
                    nestPattern = ((PathPattern) nest).getPatternString();
                } else if (nest instanceof String) {
                    nestPattern = (String) nest;
                }

                key = List.of(RESOURCE_KEY, lookupFunction, nestPattern, verb);
                entry = OtherlodeEndpoints.lookup(MODULE, key);
                if (entry == null) {
                    template = nestPattern + pattern.getPatternString();
                    if (nest instanceof PathPattern) {
                        try {
                            template = ((PathPattern) nest).combine(pattern).getPatternString();
                        } catch (RuntimeException e) {
                            // PathPattern.combine refuses some pairs, such as "/**" with "/**".
                        }
                    }
                }
            }

            result = result.doOnNext(OtherlodeEndpoints.resourceEmitted(MODULE, exchange, entry, key, verb, template));
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
