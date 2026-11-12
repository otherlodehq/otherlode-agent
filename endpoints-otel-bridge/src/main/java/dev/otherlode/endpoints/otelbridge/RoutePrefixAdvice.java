package dev.otherlode.endpoints.otelbridge;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from each OpenTelemetry route prefixer: {@code ServletContextPath.prepend} and
 * {@code JaxrsContextPath.prepend} in the Java agent's bootstrap, and {@code
 * HttpRouteSupport.prependContextPath} in the Spring MVC library instrumentation. Each takes the
 * route as its second argument and returns it with a prefix in front. The advice records the pair
 * in the table {@link OnEndAdvice} resolves the final route through.
 */
public class RoutePrefixAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(1) String route, @Advice.Return String prefixed) {
        OtherlodeEndpoints.recordRoutePrefix(prefixed, route);
    }
}
