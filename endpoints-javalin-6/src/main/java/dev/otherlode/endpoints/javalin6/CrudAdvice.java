package dev.otherlode.endpoints.javalin6;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.apibuilder.CrudHandler;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code ApiBuilder.crud(String, CrudHandler, RouteRole...)}, which the other
 * {@code crud} overloads call. It registers the five endpoints of a {@code CrudHandler} before it
 * returns, each through a lambda or a method reference of Javalin's, so the lambda's own name
 * would join to nothing of the application's. The entry opens a registration scope on this
 * thread that holds the handler and the two paths {@code crud} registers under, which are the
 * prefixed path and that path without its last segment's parameter. The exit closes it, also when
 * {@code crud} throws. {@link RegisterEndpointAdvice} reads the scope for each registration.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class CrudAdvice {
    private static final String MODULE = "javalin-6";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) String path, @Advice.Argument(1) CrudHandler crudHandler) {
        String itemPath = null;
        String collectionPath = null;
        try {
            String fullPath = ApiBuilder.prefixPath(path);
            String[] segments = fullPath.split("/");
            String resourceId = null;
            for (int i = segments.length - 1; i >= 0 && resourceId == null; i--) {
                if (!segments[i].isEmpty()) resourceId = segments[i];
            }
            if (resourceId != null) {
                itemPath = fullPath;
                collectionPath = fullPath.replace(resourceId, "");
            }
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
        OtherlodeEndpoints.enterRegistrationScope(crudHandler, itemPath, collectionPath);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
        OtherlodeEndpoints.exitRegistrationScope();
    }
}
