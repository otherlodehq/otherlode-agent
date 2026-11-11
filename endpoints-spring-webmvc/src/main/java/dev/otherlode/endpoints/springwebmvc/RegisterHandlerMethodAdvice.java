package dev.otherlode.endpoints.springwebmvc;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Woven onto {@code AbstractHandlerMethodMapping.registerHandlerMethod}, Spring MVC's registration
 * hook. It runs once per handler method as Spring builds its route table, for every
 * {@code @Controller} bean, before any request is dispatched.
 *
 * <p>The dispatch key, {@code List.of(pattern, verb)}, matches {@link HandleMatchAdvice}.
 *
 * <p>The join names the class that declares the method, since a mapping inherited from a base
 * controller or an interface default method is not declared on the bean's class. A handler whose
 * type cannot be resolved is still an endpoint, with no join.
 *
 * <p>The context path is not part of the identity. Reading it would need this advice to name a
 * servlet type and so split the module by {@code javax}/{@code jakarta} namespace. The
 * application-relative template is what the module reports.
 *
 * <p>{@code onEnter} is deliberately one flat body with no private helper method: {@link Advice}
 * inlines only the bytecode of the annotated method, so a call to a helper, even one on this class,
 * would remain a real call in the woven bytecode and require the target classloader to resolve this
 * advice class at runtime. The other advice classes of this module follow the same rule.
 */
public class RegisterHandlerMethodAdvice {
    private static final String MODULE = "spring-webmvc";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object mapping,
            @Advice.Argument(0) Object handler,
            @Advice.Argument(1) Method method,
            @Advice.Argument(2) Object info) {
        try {
            if (!(info instanceof RequestMappingInfo)) return;
            RequestMappingInfo mappingInfo = (RequestMappingInfo) info;

            Class<?> handlerType = null;
            if (handler instanceof String) {
                BeanFactory applicationContext = ((ApplicationObjectSupport) mapping).getApplicationContext();
                if (applicationContext != null) handlerType = applicationContext.getType((String) handler);
            } else if (handler != null) {
                handlerType = handler.getClass();
            }
            String beanTypeName =
                    handlerType == null ? null : ClassUtils.getUserClass(method.getDeclaringClass()).getName();
            String methodName = beanTypeName == null ? null : method.getName();

            String descriptor =
                    beanTypeName == null
                            ? null
                            : MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                    .toMethodDescriptorString();

            Set<RequestMethod> verbs = mappingInfo.getMethodsCondition().getMethods();
            for (String pattern : mappingInfo.getPatternValues()) {
                if (verbs.isEmpty()) {
                    OtherlodeEndpoints.register(
                            MODULE, List.of(pattern, "*"), "*", pattern, null, beanTypeName, methodName, descriptor);
                } else {
                    for (RequestMethod verb : verbs) {
                        String verbName = verb.name();
                        OtherlodeEndpoints.register(
                                MODULE,
                                List.of(pattern, verbName),
                                verbName,
                                pattern,
                                null,
                                beanTypeName,
                                methodName,
                                descriptor);
                    }
                }
            }
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
