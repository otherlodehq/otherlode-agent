package dev.otherlode.endpoints.micronaut;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the {@code DefaultRouter(Collection)} constructor, which every other
 * constructor reaches. The router has built all its {@code UriRouteInfo} objects by then. The
 * router goes unopened to {@link OtherlodeEndpoints#declare}, which hands it to
 * {@code MicronautModule.declare}, the one piece of this module's code that reads its routes.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DeclareRoutesAdvice {
    private static final String MODULE = "micronaut";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            OtherlodeEndpoints.declare(MODULE, router);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
