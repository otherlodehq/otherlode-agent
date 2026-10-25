package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the entry of {@code RouterFunctionMapping.setAttributes(Map, ServerRequest,
 * HandlerFunction)}, which functional routing calls once functional routing has matched a request to
 * a route and before the handler runs. Argument 0 is the exchange's attribute map, which holds the
 * matched pattern under {@link RouterFunctions#MATCHING_PATTERN_ATTRIBUTE}. A CORS preflight, an
 * {@code OPTIONS} request carrying both {@code Origin} and {@code Access-Control-Request-Method},
 * reaches this method and is no call.
 *
 * <p>A request a {@code resources(pattern, location)} route matched is counted by {@link
 * ResourceLookupAdvice}, which marks the request's exchange. Inside a nest the matching pattern is
 * the nest's own path and a filter wraps the handler, so neither would say which route served it.
 * A {@code resources(predicate, resource)} route is counted here under its own key, since its
 * predicate sets the matching pattern, and is never counted as an ordinary route that shares its
 * nest's path.
 *
 * <p>The join names the method functional routing invokes. A handler whose class is not hidden
 * reports {@code handle} and the class that declares it. A hidden class, spun for a lambda or a
 * method reference, reports the method the lambda calls, as {@link
 * OtherlodeEndpoints#lambdaImplementation} recorded it, and nothing when it recorded nothing. A
 * handler that resolves to one of Spring's own Kotlin DSL classes reports nothing. A route the walk
 * declared already carries its join, so the reflection runs only for a route found at dispatch.
 *
 * <p>The key, {@code List.of("fn", pattern, verb)}, must match the walk's. It names no handler,
 * since Spring hands a filtered route a new handler function on every request. A second lookup with
 * the verb {@code *} covers a route declared without one. {@code ServerRequest.method()} is null
 * on Spring 5.3 for a verb outside its {@code HttpMethod} enum, which only a route with no method
 * predicate can serve.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class SetAttributesAdvice {
    private static final String MODULE = "spring-webflux";
    private static final String FUNCTIONAL_KEY = "fn";
    private static final String PREDICATE_RESOURCE_KEY = "fnrespred";
    private static final String RESOURCE_HANDLER_FUNCTION =
            "org.springframework.web.reactive.function.server.ResourceHandlerFunction";
    private static final String ROUTER_DSL = "org.springframework.web.reactive.function.server.RouterFunctionDsl";
    private static final String CO_ROUTER_DSL = "org.springframework.web.reactive.function.server.CoRouterFunctionDsl";
    private static final String HANDLE_DESCRIPTOR =
            "(Lorg/springframework/web/reactive/function/server/ServerRequest;)Lreactor/core/publisher/Mono;";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) Map<String, Object> attributes,
            @Advice.Argument(1) ServerRequest request,
            @Advice.Argument(2) Object handlerFunction) {
        try {
            boolean resourceServed = OtherlodeEndpoints.takeResourceServed(request.exchange());
            if (handlerFunction == null || resourceServed) return;

            if (CorsUtils.isPreFlightRequest(request.exchange().getRequest())) return;
            HttpMethod httpMethod = request.method();

            Object patternValue = attributes.get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
            if (patternValue == null) return;
            String pattern =
                    patternValue instanceof String
                            ? (String) patternValue
                            : ((PathPattern) patternValue).getPatternString();

            String verb = httpMethod == null ? "*" : httpMethod.name();

            if (RESOURCE_HANDLER_FUNCTION.equals(handlerFunction.getClass().getName())) {
                Object resource = OtherlodeEndpoints.lookup(MODULE, List.of(PREDICATE_RESOURCE_KEY, pattern, verb));
                if (resource != null) OtherlodeEndpoints.hit(resource);
                return;
            }

            Object entry = OtherlodeEndpoints.lookup(MODULE, List.of(FUNCTIONAL_KEY, pattern, verb));
            if (entry == null) {
                entry = OtherlodeEndpoints.lookup(MODULE, List.of(FUNCTIONAL_KEY, pattern, "*"));
            }
            if (entry == null) {
                String handlerClass = null;
                String handlerMethod = null;
                String handlerDescriptor = null;
                Class<?> handlerType = handlerFunction.getClass();
                if (handlerType.isHidden()) {
                    OtherlodeEndpoints.LambdaImplementation implementation =
                            OtherlodeEndpoints.lambdaImplementation(handlerType);
                    if (implementation != null) {
                        handlerClass = implementation.className;
                        handlerMethod = implementation.methodName;
                        handlerDescriptor = implementation.descriptor;
                    }
                } else {
                    try {
                        Method method = handlerType.getMethod("handle", ServerRequest.class);
                        handlerClass = method.getDeclaringClass().getName();
                        handlerMethod = "handle";
                        handlerDescriptor = HANDLE_DESCRIPTOR;
                    } catch (NoSuchMethodException e) {
                        handlerClass = handlerType.getName();
                    }
                }
                if (handlerClass != null && (handlerClass.startsWith(ROUTER_DSL) || handlerClass.startsWith(CO_ROUTER_DSL))) {
                    handlerClass = null;
                    handlerMethod = null;
                    handlerDescriptor = null;
                }
                String countedVerb = OtherlodeEndpoints.standardVerb(verb);
                entry = OtherlodeEndpoints.recordDispatch(
                        MODULE, List.of(FUNCTIONAL_KEY, pattern, countedVerb), countedVerb, pattern, null, handlerClass);
                if (entry != null) {
                    OtherlodeEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
                }
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
