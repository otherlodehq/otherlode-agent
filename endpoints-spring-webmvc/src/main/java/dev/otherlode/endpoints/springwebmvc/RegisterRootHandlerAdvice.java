package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ClassUtils;

/**
 * Runs on exit from {@code AbstractUrlHandlerMapping.setRootHandler(Object)}. It declares the
 * root endpoint, verb {@code "*"} and template {@code "/"}, keyed by the handler itself. A handler
 * still named by its bean gets the fixed key {@code List.of("root-by-name")} and has no class join.
 * A null handler removes the root handler and declares nothing.
 */
public class RegisterRootHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
