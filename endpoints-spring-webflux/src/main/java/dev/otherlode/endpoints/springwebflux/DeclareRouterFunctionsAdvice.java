package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code RouterFunctionMapping.afterPropertiesSet()}, which fills the
 * private {@code routerFunction} field from every {@code RouterFunction} bean in the application
 * context, unless the mapping was built with a router function, which it leaves as given. Either
 * way the field is set when this method returns, before any request is dispatched.
 *
 * <p>{@code routerFunction} is bound as a plain {@code Object}, so this class carries no symbolic
 * reference to {@code RouterFunction}. The object goes unopened to {@link
 * OtherlodeEndpoints#declare}, which hands it to {@code SpringWebFluxModule.declare}, the one piece
 * of this module's code that walks it: a {@code RouterFunction} only reveals its routes to a
 * visitor, which advice cannot implement.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
 */
public class DeclareRouterFunctionsAdvice {
    private static final String MODULE = "spring-webflux";

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
