package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

/**
 * Woven onto the exit of {@code AbstractUrlHandlerMapping.setRootHandler(Object)}, which {@code
 * registerHandler("/", ...)} calls and which a mapping may call itself, as Spring Boot's welcome
 * page mapping does. It declares the root endpoint, verb {@code "*"} and template {@code "/"},
 * keyed by the handler itself. A handler still named by its bean gets the fixed key {@code
 * List.of("root-by-name")} and has no class join. A null handler removes the root handler and
 * declares nothing.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class RegisterRootHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object rootHandler) {
        try {
            if (rootHandler == null) return;
            String handlerClassName =
                    rootHandler instanceof String ? null : ClassUtils.getUserClass(rootHandler.getClass()).getName();
            Object key = rootHandler instanceof String ? List.of("root-by-name") : List.of("root", rootHandler);
            OtherlodeEndpoints.register(MODULE, key, "*", "/", null, handlerClassName, null, null);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
