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
 * HttpServletRequest)}, Spring MVC's dispatch point. It runs once per matched request, with the
 * winning {@link RequestMappingInfo} narrowed to the patterns that matched, best match first, and
 * the matched verb, before the handler runs.
 *
 * <p>This advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones, by prefix, when the woven method takes them, so one class covers every Spring
 * Framework major version this agent supports. A lookup {@code HandlerMappingIntrospector} makes
 * for Spring Security or CORS arrives with the request wrapped in its own request class, an async
 * redispatch has the dispatcher type {@code ASYNC}, and a CORS preflight is answered by Spring's
 * preflight handler. None of the three is a call of the route it matches. Neither is a second
 * lookup on a request that is still a {@code REQUEST} dispatch but already carries {@code
 * HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}, which only the first lookup sets. OpenTelemetry's
 * Spring MVC library instrumentation makes that lookup from its filter, after the dispatch, on the
 * container's request, to learn the route. A forward or an include has its own dispatcher type and
 * counts.
 *
 * <p>The {@link RequestMappingInfo} handed to this method is a new object built per request by
 * {@code getMatchingCondition}, never the one {@link RegisterHandlerMethodAdvice} saw at
 * registration, so it cannot serve as a dispatch key by identity. The same {@code
 * List.of(pattern, verb)} key is rebuilt here instead, from the same two accessors {@link
 * RegisterHandlerMethodAdvice} used, so both sides agree by value. This allocates one small list
 * per request, accepted the same way {@link RegisterHandlerMethodAdvice} accepts its own
 * per-registration allocation, since a request already allocates far more than this to reach here.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class HandleMatchAdvice {
    private static final String MODULE = "spring-webmvc";
    private static final String INTROSPECTOR_REQUEST =
            "org.springframework.web.servlet.handler.HandlerMappingIntrospector$AttributesPreservingRequest";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) RequestMappingInfo info,
            @Advice.Argument(2) HttpServletRequest request) {
        try {
            if (INTROSPECTOR_REQUEST.equals(request.getClass().getName())
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
