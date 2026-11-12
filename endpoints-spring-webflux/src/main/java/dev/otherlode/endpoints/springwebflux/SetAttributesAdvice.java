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
 * Runs on entry to {@code RouterFunctionMapping.setAttributes(Map, ServerRequest,
 * HandlerFunction)}, which functional routing calls once it has matched a request to a route and
 * before the handler runs. A CORS preflight counts nothing.
 *
 * <p>{@link ResourceLookupAdvice} counts a request a {@code resources(pattern, location)} route
 * matched, and marks the request's exchange. This advice counts a {@code resources(predicate,
 * resource)} route under its own key.
 *
 * <p>The join names the method functional routing invokes. A handler whose class is not hidden
 * reports {@code handle} and the class that declares it. A hidden class reports the method the
 * lambda calls, as {@link OtherlodeEndpoints#lambdaImplementation} recorded it, and nothing when it
 * recorded nothing. A handler that resolves to one of Spring's own Kotlin DSL classes reports
 * nothing. A route the walk declared already carries its join.
 *
 * <p>The key, {@code List.of("fn", pattern, verb)}, matches the walk's. A second lookup with the
 * verb {@code *} covers a route declared without one.
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

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
