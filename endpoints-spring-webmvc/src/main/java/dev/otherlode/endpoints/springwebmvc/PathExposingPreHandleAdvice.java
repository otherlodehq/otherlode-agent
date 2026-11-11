package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

/**
 * Woven onto {@code AbstractUrlHandlerMapping$PathExposingHandlerInterceptor.preHandle}, the
 * dispatch point shared by every URL-mapped handler mapping. Only {@code DispatcherServlet} runs an
 * interceptor, once per dispatch, so a lookup by {@code HandlerMappingIntrospector} never reaches
 * it. A request stopped before the interceptors run, by a {@code Last-Modified} answer of 304
 * (Spring 5.3 and 6) or a refused CORS request, is not counted. An async redispatch and a CORS
 * preflight count nothing.
 *
 * <p>The advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones by prefix.
 *
 * <p>The key, {@code List.of(bestMatchingPattern, "*")}, matches {@link RegisterUrlHandlerAdvice}.
 * A lookup miss means a handler this module did not see register, and the dispatch is recorded.
 *
 * <p>When no registered path matches, Spring falls back to the root handler for {@code "/"} or the
 * {@code "/*"} handler for anything else, and passes the raw lookup path as both the pattern and
 * the path within the mapping. A miss whose two strings are equal is therefore a fallback. A
 * fallback for {@code "/"} served by a root handler {@link RegisterRootHandlerAdvice} declared
 * counts against {@code "/"}, looked up by that handler, and any other fallback against {@code
 * "/*"}.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class PathExposingPreHandleAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.FieldValue("bestMatchingPattern") String bestMatchingPattern,
            @Advice.FieldValue("pathWithinMapping") String pathWithinMapping,
            @Advice.Argument(0) HttpServletRequest request,
            @Advice.Argument(2) Object rawHandler) {
        try {
            if (request.getDispatcherType() == DispatcherType.ASYNC
                    || ("OPTIONS".equals(request.getMethod())
                            && request.getHeader("Origin") != null
                            && request.getHeader("Access-Control-Request-Method") != null)) {
                return;
            }

            String pattern = bestMatchingPattern;
            List<String> key = List.of(pattern, "*");
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null && pattern.equals(pathWithinMapping)) {
                if ("/".equals(pattern) && rawHandler != null) {
                    entry = OtherlodeEndpoints.lookup(MODULE, List.of("root", rawHandler));
                    if (entry == null) entry = OtherlodeEndpoints.lookup(MODULE, List.of("root-by-name"));
                }
                if (entry == null) {
                    pattern = "/*";
                    key = List.of(pattern, "*");
                    entry = OtherlodeEndpoints.lookup(MODULE, key);
                }
            }
            if (entry == null) {
                String handlerClassName =
                        (rawHandler == null || rawHandler instanceof String)
                                ? null
                                : ClassUtils.getUserClass(rawHandler.getClass()).getName();
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, "*", pattern, null, handlerClassName);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
