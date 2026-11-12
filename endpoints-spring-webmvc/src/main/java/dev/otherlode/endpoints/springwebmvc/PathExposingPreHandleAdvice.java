package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

// Advice inlines only this method's bytecode into the woven method, so onEnter stays one flat body with no helpers.
// The key matches RegisterUrlHandlerAdvice's. A miss whose pattern equals the path within the mapping is
// Spring's fallback to the root handler or the "/*" handler.
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
