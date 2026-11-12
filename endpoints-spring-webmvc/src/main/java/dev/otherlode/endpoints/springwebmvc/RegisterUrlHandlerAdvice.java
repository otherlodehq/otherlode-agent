package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;

/**
 * Runs on entry to {@code AbstractUrlHandlerMapping.registerHandler(String, Object)}, the registration
 * hook shared by every URL-mapped handler mapping.
 *
 * <p>Every endpoint declared here uses verb {@code "*"} and joins to the handler's class only.
 */
public class RegisterUrlHandlerAdvice {
    private static final String MODULE = "spring-webmvc";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
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
