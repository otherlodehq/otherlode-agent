package dev.otherlode.endpoints.jaxrs;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to every JAX-RS resource method that {@code JaxRsModule} declared and registered
 * at transform time.
 *
 * <p>When {@link OtherlodeEndpoints#lookup} finds no registration for the key, this records the
 * dispatch with the key as the verbatim template and verb {@code "*"}, so the hit still counts.
 */
public class ResourceMethodAdvice {
    private static final String MODULE = "jaxrs";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t\\##m#d") String key) {
        try {
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = OtherlodeEndpoints.recordDispatch(MODULE, key, "*", key, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
