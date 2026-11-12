package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/**
 * Woven onto the exit of {@code PathResourceLookupFunction.apply(ServerRequest)}, the lookup
 * function behind {@code RouterFunctions.resources(pattern, location)}. A resource is found only
 * when the returned {@code Mono<Resource>} emits, so the advice replaces the Mono with {@code
 * mono.doOnNext(consumer)}, where {@link OtherlodeEndpoints#resourceEmitted} builds the consumer.
 * The woven method behaves as the unwoven one apart from counting, and the consumer never throws.
 *
 * <p>On each emitted resource the consumer marks the request's exchange, so {@link
 * SetAttributesAdvice} does not count it under the nest's path, and then counts the endpoint. A
 * preflight and a verb the resource handler refuses count nothing.
 *
 * <p>The key, {@code List.of("fnres", lookupFunction, nestPattern, verb)}, matches the walk's, and
 * the registry holds the lookup function weakly. An endpoint the walk did not declare is recorded
 * as discovered when the Mono emits, so a lookup that finds nothing creates none.
 */
public class ResourceLookupAdvice {
    private static final String MODULE = "spring-webflux";
    private static final String RESOURCE_KEY = "fnres";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
                            && !CorsUtils.isPreFlightRequest(((ServerWebExchange) exchange).getRequest());

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
