package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

/**
 * Woven onto the exit of {@code AbstractUrlHandlerMapping.setRootHandler(Object)}, which {@code
 * registerHandler("/", ...)} calls and which a mapping may call itself, as Spring Boot's welcome
 * page mapping does. It declares the root endpoint, verb {@code "*"} and template {@code "/"},
 * under a key holding the handler itself, so {@link PathExposingPreHandleAdvice} counts a request
 * against {@code "/"} only when this handler served it, and a default handler's {@code "/"}
 * against {@code "/*"}. A handler still named by its bean, as lazy handlers are, is resolved to
 * the bean only at dispatch, so it is keyed by name alone and any {@code "/"} fallback counts
 * against it.
 *
 * <p>A handler registered by bean name arrives resolved from {@code registerHandler}, or still a
 * name, which carries no class here; {@link RegisterUrlHandlerAdvice} has already joined that
 * case. A null handler removes the root handler and declares nothing.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
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
