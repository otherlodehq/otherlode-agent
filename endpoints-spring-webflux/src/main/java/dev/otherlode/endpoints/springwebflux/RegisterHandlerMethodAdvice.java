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
 * Woven onto the exit of {@code AbstractHandlerMethodMapping.registerHandlerMethod}, the
 * registration hook for every annotated controller method Spring WebFlux detects. It runs once per
 * handler method as the route table is built, and does not run for a registration that throws.
 *
 * <p>Reactive patterns are always {@code PathPattern}s. A mapping with no path holds the empty
 * pattern, which the seam normalises to {@code /}.
 *
 * <p>The dispatch key, {@code List.of(pattern, verb)}, must match {@link HandleMatchAdvice}: both
 * build it from the same two accessors, and {@link List#equals} gives value equality with no key
 * type of our own that the framework's classloader would need to see.
 *
 * <p>The join names the class that declares the method, where its probe lives. A handler bean whose
 * type cannot be resolved is still an endpoint, with no join.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own: {@link
 * Advice} inlines only the bytecode of the annotated method, so a call to a helper, even one on this
 * class, would remain a real call in the woven bytecode and require the target classloader to
 * resolve this advice class at runtime.
 */
public class RegisterHandlerMethodAdvice {
    private static final String MODULE = "spring-webflux";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
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
