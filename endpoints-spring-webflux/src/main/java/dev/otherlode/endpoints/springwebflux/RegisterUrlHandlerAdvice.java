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
 * registration hook shared by every URL-mapped handler mapping, {@code SimpleUrlHandlerMapping}
 * and Spring Boot's static-resource and webjars mappings among them. The {@code String[]} overload
 * delegates to this one, so advising only this signature covers both once. The advice does not
 * run for a registration that throws.
 *
 * <p>A URL-mapped handler carries no verb, so the endpoint has the verb {@code *}. Spring stores
 * the pattern after {@code initFullPathPattern}, which only adds a leading slash, and a request
 * reports that stored string as its best matching pattern, so the key is built from the same
 * call. The join is the handler's class alone.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see
 * {@link RegisterHandlerMethodAdvice}'s Javadoc for why.
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
