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
 * Runs on exit from {@code PathResourceLookupFunction.apply(ServerRequest)}, the lookup
 * function behind {@code RouterFunctions.resources(pattern, location)}. The dispatch key is {@code
 * List.of("fnres", lookupFunction, nestPattern, verb)} and must match the key in {@code
 * SpringWebMvcModule.declare}.
 *
 * <p>A request counts only for {@code GET}, {@code HEAD} and {@code OPTIONS}. An introspector
 * lookup, an async redispatch and a CORS preflight are no call. Every present result marks the
 * request through {@link OtherlodeEndpoints#resourceServed}, so {@link SetAttributesAdvice} does
 * not count it again. The advice compiles against the {@code javax} servlet types, and the module
 * binds it to the {@code jakarta} ones, by prefix, when {@code ServerRequest.servletRequest()}
 * returns them.
 */
public class ResourceLookupAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String RESOURCE_KEY = "fnres";
    private static final String INTROSPECTOR_REQUEST =
            "org.springframework.web.servlet.handler.HandlerMappingIntrospector$AttributesPreservingRequest";
    private static final String DISPATCHER_CONTEXT = "org.springframework.web.servlet.DispatcherServlet.CONTEXT";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
