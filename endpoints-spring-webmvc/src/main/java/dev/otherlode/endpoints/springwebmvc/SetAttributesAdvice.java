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
 * HandlerFunction)}, the point functional routing has already matched a request to a route and is
 * about to hand it to the winning handler. It runs once per matched request, before the handler
 * runs.
 *
 * <p>This advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones, by prefix, when the woven method takes them. A lookup {@code
 * HandlerMappingIntrospector} makes for Spring Security or CORS arrives with the request wrapped
 * in its own request class, an async redispatch has the dispatcher type {@code ASYNC}, and a CORS
 * preflight is answered by Spring's preflight handler. None of the three is a call. A request a
 * {@code resources(pattern, location)} route matched is counted by {@link ResourceLookupAdvice},
 * which marks it: inside a nest its matching pattern is the nest's own path, and a filter wraps its
 * handler, so neither would say which route served it. A resource handler this module did not
 * declare, behind a lookup function the walk could not read, is not discovered here either. A
 * {@code resources(predicate, resource)} route is counted here, under its own key, since its
 * predicate sets the matching pattern; a resource handler is never counted as an ordinary route
 * that shares its nest's path.
 *
 * <p>The matched pattern lives under {@link RouterFunctions#MATCHING_PATTERN_ATTRIBUTE} in {@code
 * request.attributes()} at this point; {@code setAttributes} itself only removes that attribute
 * after this advice's own entry point has already run. Its value is a {@code PathPattern} on
 * every version this advice was checked against, but is read defensively as either a {@code
 * PathPattern} or a plain {@code String}, since nothing in the surrounding API contract promises
 * the attribute stays a {@code PathPattern} forever.
 *
 * <p>The handler join names the method functional routing actually invokes: a {@code
 * HandlerFunction} whose class is not hidden reports {@code handle} and the class that declares
 * it, read through {@code getMethod("handle", ServerRequest.class)} since every implementation
 * must override that method publicly to satisfy the interface. A hidden class, spun for a Java or
 * Kotlin lambda or method reference through {@code invokedynamic}, has no stable name across runs.
 * For one of those, the join names the method the lambda calls, as {@link
 * OtherlodeEndpoints#lambdaImplementation} recorded it when the JDK spun the class. When nothing
 * was recorded, the class, method, and descriptor are all null. A handler that resolves to Spring's
 * own Kotlin DSL class, {@code RouterFunctionDsl}, gets no join either. A route the declare walk
 * registered already carries this join from registration, so the reflection here runs only for a
 * route discovered at dispatch, never on the per-request path, which stays one attribute read and
 * a lookup.
 *
 * <p>The dispatch key built here, {@code List.of("fn", pattern, verb)}, must match {@code
 * SpringWebMvcModule.declare}'s own key exactly, the same value-equality convention the
 * annotation-mapped side of this module uses for {@link HandleMatchAdvice} and {@link
 * RegisterHandlerMethodAdvice}. It names no handler, since Spring hands a filtered route a new
 * handler function on every request. A second lookup with verb {@code "*"} covers a route declared as
 * unconstrained; either miss means a route this module has not seen register, discovered here
 * instead of dropped.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
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
            // Spring passes a null handler when no route matched.
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
                    // Spring Security's firewall throws for a header value it refuses; Spring refuses the request after this.
                    return;
                }
            }
            Object patternValue = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
            if (patternValue == null) return;
            String pattern =
                    patternValue instanceof String
                            ? (String) patternValue
                            : ((PathPattern) patternValue).getPatternString();

            // Spring 5.3 returns null for a verb outside its HttpMethod enum (PROPFIND, say), which
            // only a route with no method predicate can serve, so it is looked up as "*".
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
