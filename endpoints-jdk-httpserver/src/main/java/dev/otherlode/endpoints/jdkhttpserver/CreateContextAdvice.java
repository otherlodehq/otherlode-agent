package dev.otherlode.endpoints.jdkhttpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from both overloads of {@code ServerImpl.createContext} and reports the new context
 * as a registered endpoint. A context is a prefix match with no verb constraint, so the verb is
 * {@code "*"} and the verbatim template is the context's path as registered. A context from the
 * one-argument overload has no handler, so this reports it with a null handler class, and {@link
 * SetHandlerAdvice} fills the class in once the context gets a handler.
 *
 * <p>A handler class that is not hidden joins to {@code handle} and the class that declares it. A
 * hidden class (a lambda or method reference) joins to the method the lambda calls, as {@link
 * OtherlodeEndpoints#lambdaImplementation} recorded it. When nothing was recorded, the class,
 * method and descriptor are all null.
 */
public class CreateContextAdvice {
    private static final String MODULE = "jdk-httpserver";
    private static final String HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V";

    // Advice inlines only this method's bytecode, and the woven JDK class cannot resolve a helper
    // class from this module. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return HttpContext context) {
        try {
            HttpHandler handler = context.getHandler();
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            if (handler != null) {
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
            }
            OtherlodeEndpoints.register(
                    MODULE, context, "*", context.getPath(), null, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
