package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto {@code RouterFunctionMapping.setAttributes(HttpServletRequest, ServerRequest,
 * HandlerFunction)}, where functional routing has matched a request to a route and is about to hand
 * it to the handler. It runs once per matched request.
 *
 * <p>The advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones, by prefix, when the woven method takes them. An introspector lookup, an async
 * redispatch and a CORS preflight are no call. A request a {@code resources(pattern, location)}
 * route matched is counted by {@link ResourceLookupAdvice}, not here. A {@code
 * resources(predicate, resource)} route is counted here under its own key.
 *
 * <p>The dispatch key is {@code List.of("fn", pattern, verb)} and must match the key in {@code
 * SpringWebMvcModule.declare}. It names no handler, since Spring hands a filtered route a new handler
 * function on every request. A second lookup with verb {@code "*"} covers a route declared as
 * unconstrained. A route found by neither lookup is recorded at dispatch. Its handler join is the
 * method named by {@link OtherlodeEndpoints#lambdaImplementation} for a hidden lambda class, or
 * {@code handle} on the class that declares that method otherwise. A handler of Spring's {@code
 * RouterFunctionDsl} gets no join.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class SetAttributesAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String FUNCTIONAL_KEY = "fn";
    private static final String PREDICATE_RESOURCE_KEY = "fnrespred";
    private static final String INTROSPECTOR_REQUEST =
            "org.springframework.web.servlet.handler.HandlerMappingIntrospector$AttributesPreservingRequest";
    private static final String DISPATCHER_CONTEXT = "org.springframework.web.servlet.DispatcherServlet.CONTEXT";
    private static final String RESOURCE_HANDLER_FUNCTION = "org.springframework.web.servlet.function.ResourceHandlerFunction";
    private static final String ROUTER_DSL = "org.springframework.web.servlet.function.RouterFunctionDsl";
    private static final String HANDLE_DESCRIPTOR =
            "(Lorg/springframework/web/servlet/function/ServerRequest;)Lorg/springframework/web/servlet/function/ServerResponse;";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) HttpServletRequest servletRequest,
            @Advice.Argument(1) ServerRequest request,
            @Advice.Argument(2) Object handlerFunction) {
        try {
            boolean resourceServed = OtherlodeEndpoints.takeResourceServed(servletRequest);
            if (handlerFunction == null || resourceServed) return;
            if (INTROSPECTOR_REQUEST.equals(servletRequest.getClass().getName())
                    || servletRequest.getAttribute(DISPATCHER_CONTEXT) == null
                    || servletRequest.getDispatcherType() == DispatcherType.ASYNC) {
                return;
            }
            if ("OPTIONS".equals(servletRequest.getMethod())) {
                try {
                    if (servletRequest.getHeader("Origin") != null
                            && servletRequest.getHeader("Access-Control-Request-Method") != null) {
                        return;
                    }
                } catch (RuntimeException e) {
                    return;
                }
            }
            Object patternValue = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
            if (patternValue == null) return;
            String pattern =
                    patternValue instanceof String
                            ? (String) patternValue
                            : ((PathPattern) patternValue).getPatternString();

            HttpMethod httpMethod = request.method();
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
                    OtherlodeEndpoints.LambdaImplementation implementation = OtherlodeEndpoints.lambdaImplementation(handlerType);
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
                if (handlerClass != null && handlerClass.startsWith(ROUTER_DSL)) {
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
