package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code RouterFunctionMapping.afterPropertiesSet()}, which fills the
 * private {@code routerFunction} field.
 *
 * <p>The advice passes the field's object, unopened, to {@link OtherlodeEndpoints#declare}.
 */
public class DeclareRouterFunctionsAdvice {
    private static final String MODULE = "spring-webflux";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
