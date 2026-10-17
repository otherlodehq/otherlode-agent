package dev.otherlode.endpoints.springwebflux;

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
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Woven onto the exit of {@code AbstractHandlerMethodMapping.registerMapping(mapping, handler,
 * method)}, the public method for registering a handler method in code. Spring Boot Actuator
 * registers every operation this way, and it never passes through {@code registerHandlerMethod},
 * so without this advice those endpoints would be found only once called.
 *
 * <p>The body is {@link RegisterHandlerMethodAdvice}'s with the arguments in this method's order.
 */
public class RegisterMappingAdvice {
    private static final String MODULE = "spring-webflux";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object mapping,
            @Advice.Argument(0) Object info,
            @Advice.Argument(1) Object handler,
            @Advice.Argument(2) Method method) {
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
            for (PathPattern pathPattern : mappingInfo.getPatternsCondition().getPatterns()) {
                String pattern = pathPattern.getPatternString();
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
