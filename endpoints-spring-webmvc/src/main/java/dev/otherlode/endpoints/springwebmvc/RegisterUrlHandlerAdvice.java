package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;

/**
 * Woven onto {@code AbstractUrlHandlerMapping.registerHandler(String, Object)}, the registration
 * hook shared by every URL-mapped handler mapping: {@code SimpleUrlHandlerMapping}, {@code
 * BeanNameUrlHandlerMapping}, and Spring Boot's static-resource and webjars mappings. It runs once
 * per URL path. The {@code registerHandler(String[], String)} overload delegates to this one.
 *
 * <p>Every endpoint declared here uses verb {@code "*"} and joins to the handler's class only.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class RegisterUrlHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
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

            List<String> key = List.of(urlPath, "*");
            OtherlodeEndpoints.register(MODULE, key, "*", urlPath, null, handlerClassName, null, null);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
