package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Iterator;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Woven onto {@code RequestMappingInfoHandlerMapping.handleMatch(RequestMappingInfo, String,
 * HttpServletRequest)}, Spring MVC's dispatch point.
 *
 * <p>A lookup that is not a call of the route it matches counts nothing: one made by {@code
 * HandlerMappingIntrospector}, an async redispatch, a CORS preflight, a lookup made before {@code
 * DispatcherServlet.doService} set {@code DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE} on
 * the request, and a second lookup on a {@code REQUEST} dispatch that already carries {@code
 * HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}. A forward or an include counts.
 *
 * <p>The advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones by prefix.
 *
 * <p>The {@link RequestMappingInfo} is built per request, so the key is rebuilt by value as {@code
 * List.of(pattern, verb)}, the key {@link RegisterHandlerMethodAdvice} registers.
 */
public class HandleMatchAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String INTROSPECTOR_REQUEST =
            "org.springframework.web.servlet.handler.HandlerMappingIntrospector$AttributesPreservingRequest";
    private static final String DISPATCHER_CONTEXT = "org.springframework.web.servlet.DispatcherServlet.CONTEXT";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) RequestMappingInfo info,
            @Advice.Argument(2) HttpServletRequest request) {
        try {
            if (INTROSPECTOR_REQUEST.equals(request.getClass().getName())
                    || request.getAttribute(DISPATCHER_CONTEXT) == null
                    || request.getDispatcherType() == DispatcherType.ASYNC
                    || (request.getDispatcherType() == DispatcherType.REQUEST
                            && request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) != null)
                    || ("OPTIONS".equals(request.getMethod())
                            && request.getHeader("Origin") != null
                            && request.getHeader("Access-Control-Request-Method") != null)) {
                return;
            }

            // The best match comes first in the condition's own sorted set. getPatternValues()
            // copies the parsed patterns into a HashSet, which loses that order.
            String pattern;
            PathPatternsRequestCondition parsed = info.getPathPatternsCondition();
            if (parsed != null) {
                pattern = parsed.getFirstPattern().getPatternString();
            } else {
                PatternsRequestCondition legacy = info.getPatternsCondition();
                if (legacy == null) return;
                Iterator<String> patterns = legacy.getPatterns().iterator();
                if (!patterns.hasNext()) return;
                pattern = patterns.next();
            }

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
