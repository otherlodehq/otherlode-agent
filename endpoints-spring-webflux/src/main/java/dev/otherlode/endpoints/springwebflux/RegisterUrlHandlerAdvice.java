package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;

/**
 * Woven onto the exit of {@code AbstractUrlHandlerMapping.registerHandler(String, Object)}, the
 * registration hook shared by every URL-mapped handler mapping. The {@code String[]} overload
 * delegates to this one. The advice does not run for a registration that throws.
 *
 * <p>A URL-mapped handler carries no verb, so the endpoint has the verb {@code *}. The key is built
 * from {@code initFullPathPattern}, the same call Spring uses for the pattern a request reports as
 * its best match. The join is the handler's class alone.
 *
 * <p>The advice body is inlined into the woven method, so {@code onExit} stays one flat body with
 * no helper methods.
 */
public class RegisterUrlHandlerAdvice {
    private static final String MODULE = "spring-webflux";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object mapping,
            @Advice.Argument(0) String urlPath,
            @Advice.Argument(1) Object handler) {
        try {
            Class<?> handlerType = null;
            if (handler instanceof String) {
                BeanFactory applicationContext = ((ApplicationObjectSupport) mapping).getApplicationContext();
                if (applicationContext != null) handlerType = applicationContext.getType((String) handler);
            } else if (handler != null) {
                handlerType = handler.getClass();
            }
            String handlerClassName = handlerType == null ? null : ClassUtils.getUserClass(handlerType).getName();

            String pattern = ((AbstractHandlerMapping) mapping).getPathPatternParser().initFullPathPattern(urlPath);
            List<String> key = List.of(pattern, "*");
            OtherlodeEndpoints.register(MODULE, key, "*", pattern, null, handlerClassName, null, null);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
