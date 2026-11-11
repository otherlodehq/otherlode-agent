package dev.otherlode.endpoints.javalin7;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.apibuilder.ApiBuilder;
import io.javalin.apibuilder.CrudHandler;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code ApiBuilder.crud(String, CrudHandler, RouteRole...)}. The entry opens a
 * registration scope on this thread that holds the handler and the two paths {@code crud}
 * registers under. The exit closes it, also when {@code crud} throws. {@link
 * RegisterEndpointAdvice} reads the scope for each registration.
 */
public class CrudAdvice {
    private static final String MODULE = "javalin-7";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
