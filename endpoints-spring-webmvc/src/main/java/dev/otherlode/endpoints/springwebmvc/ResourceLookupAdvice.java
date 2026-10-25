package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import java.util.Optional;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the exit of {@code PathResourceLookupFunction.apply(ServerRequest)}, the lookup
 * function behind {@code RouterFunctions.resources(pattern, location)}. A present result means
 * the route function returns this resource route: composition, nesting, filtering and attributes
 * each return the first present result and evaluate a later route only when the earlier one is
 * empty. Spring builds a new {@code ResourceHandlerFunction} per request and sets no matching
 * pattern of its own, so neither is a dispatch key. The key is the lookup function with the path
 * of the nest it was reached through, which a nested request carries as its matching pattern, and
 * the verb. The declare walk registered the same key for each nest path it found.
 *
 * <p>The dispatch key built here, {@code List.of("fnres", lookupFunction, nestPattern, verb)},
 * must match the key in {@code SpringWebMvcModule.declare}. The registry holds the lookup function
 * weakly. The handler serves {@code GET}, {@code HEAD} and {@code OPTIONS}; a request with another
 * verb is answered 405 and is no call of an endpoint this module declares.
 *
 * <p>Every present result marks the request through {@link OtherlodeEndpoints#resourceServed}, so
 * the route mapping's {@code setAttributes} hook does not count it again under the nest's path. An
 * introspector lookup, an async redispatch and a CORS preflight are no call. This advice is
 * written against the {@code javax} servlet types and bound to the {@code jakarta} ones, by
 * prefix, when {@code ServerRequest.servletRequest()} returns them.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class ResourceLookupAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String RESOURCE_KEY = "fnres";
    private static final String INTROSPECTOR_REQUEST =
            "org.springframework.web.servlet.handler.HandlerMappingIntrospector$AttributesPreservingRequest";
    private static final String DISPATCHER_CONTEXT = "org.springframework.web.servlet.DispatcherServlet.CONTEXT";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object lookupFunction,
            @Advice.FieldValue("pattern") PathPattern pattern,
            @Advice.Argument(0) ServerRequest request,
            @Advice.Return Optional<?> result) {
        try {
            if (result == null || !result.isPresent()) return;
            HttpServletRequest servletRequest = request.servletRequest();
            OtherlodeEndpoints.resourceServed(servletRequest);
            String verb = servletRequest.getMethod();
            if (INTROSPECTOR_REQUEST.equals(servletRequest.getClass().getName())
                    || servletRequest.getAttribute(DISPATCHER_CONTEXT) == null
                    || servletRequest.getDispatcherType() == DispatcherType.ASYNC
                    || !("GET".equals(verb) || "HEAD".equals(verb) || "OPTIONS".equals(verb))) {
                return;
            }
            if ("OPTIONS".equals(verb)) {
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

            Object nest = request.attributes().get(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE);
            String nestPattern = "";
            if (nest instanceof PathPattern) {
                nestPattern = ((PathPattern) nest).getPatternString();
            } else if (nest instanceof String) {
                nestPattern = (String) nest;
            }

            List<Object> key = List.of(RESOURCE_KEY, lookupFunction, nestPattern, verb);
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                String template = nestPattern + pattern.getPatternString();
                if (nest instanceof PathPattern) {
                    try {
                        template = ((PathPattern) nest).combine(pattern).getPatternString();
                    } catch (RuntimeException e) {
                        // PathPattern.combine refuses some pairs, such as "/**" with "/**".
                    }
                }
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, verb, template, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
