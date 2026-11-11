package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouterFunctionMapping.afterPropertiesSet()}, which fills the
 * private {@code routerFunction} field. The field is set when this method returns, before any
 * request is dispatched.
 *
 * <p>The field is bound as a plain {@code Object}, so this class has no symbolic reference to
 * {@code RouterFunction}. The object goes unopened to {@link OtherlodeEndpoints#declare}, which
 * hands it to {@code SpringWebMvcModule.declare}, the only code that walks it.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class DeclareRouterFunctionsAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.FieldValue("routerFunction") Object routerFunction) {
        try {
            if (routerFunction != null) {
                OtherlodeEndpoints.declare(MODULE, routerFunction);
            }
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
