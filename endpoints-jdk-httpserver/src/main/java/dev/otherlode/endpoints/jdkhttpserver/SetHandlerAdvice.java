package dev.otherlode.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code HttpContextImpl.setHandler} and attaches a handler class to a context
 * that {@link CreateContextAdvice} registered without one. The handler joins as it does there.
 *
 * <p>The advice runs only on a normal return, so a second handler, which {@code setHandler}
 * refuses with an {@code IllegalArgumentException}, never becomes the join.
 */
public class SetHandlerAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    // Advice inlines only this method's bytecode, and the woven JDK class cannot resolve a helper
    // class from this module. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This HttpContext context, @Advice.Argument(0) HttpHandler handler) {
        try {
            if (handler == null) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, context);
            if (entry == null) return;
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            Class<?> handlerType = handler.getClass();
            if (handlerType.isHidden()) {
                OtherlodeEndpoints.LambdaImplementation implementation = OtherlodeEndpoints.lambdaImplementation(handlerType);
                if (implementation != null) {
                    handlerClass = implementation.className;
                    handlerMethod = implementation.methodName;
                    handlerDescriptor = implementation.descriptor;
                }
            } else {
                try {
                    Method method = handlerType.getMethod("handle", HttpExchange.class);
                    handlerClass = method.getDeclaringClass().getName();
                    handlerMethod = "handle";
                    handlerDescriptor = HANDLE_DESCRIPTOR;
                } catch (NoSuchMethodException e) {
                    handlerClass = handlerType.getName();
                }
            }
            OtherlodeEndpoints.attachHandler(MODULE, entry, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
