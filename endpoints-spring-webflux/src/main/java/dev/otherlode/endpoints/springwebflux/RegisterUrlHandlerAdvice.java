package dev.otherlode.endpoints.springwebflux;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;

/**
 * Runs on exit from {@code AbstractUrlHandlerMapping.registerHandler(String, Object)}, the
 * registration hook shared by every URL-mapped handler mapping. The advice does not run for a
 * registration that throws.
 *
 * <p>A URL-mapped handler carries no verb, so the endpoint has the verb {@code *}. The join is the
 * handler's class alone.
 */
public class RegisterUrlHandlerAdvice {
    private static final String MODULE = "spring-webflux";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
