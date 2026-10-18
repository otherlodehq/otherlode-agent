package dev.otherlode.endpoints.micronaut3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the {@code DefaultRouter(Collection)} constructor, which every other
 * constructor reaches. The router has built all its {@code UriRoute} objects by then. The router
 * goes unopened to {@link OtherlodeEndpoints#declare}, which hands it to
 * {@code Micronaut3Module.declare}, which holds it until a dispatch point is constructed and then
 * reads its routes.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class DeclareRoutesAdvice {
    private static final String MODULE = "micronaut-3";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            OtherlodeEndpoints.declare(MODULE, router);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
