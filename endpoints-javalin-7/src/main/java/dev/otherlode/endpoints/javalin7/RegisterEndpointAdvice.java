package dev.otherlode.endpoints.javalin7;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from the {@code ParsedEndpoint(Endpoint, RouterConfig)} constructor. The instance
 * is the dispatch key. It declares only an endpoint whose method {@code isHttpMethod()}, so it
 * skips handlers such as before, after, beforeMatched, afterMatched and WebSocket upgrade handlers.
 *
 * <p>The handler join names {@code handle} and the class declaring it for a named class, and the
 * method the lambda calls for a hidden one. An endpoint whose {@code handle} is declared by
 * {@code SseHandler} or {@code VueHandler} gets no join. While {@code ApiBuilder.crud} runs on
 * this thread, the endpoints it registers through lambdas join to the {@code CrudHandler}'s
 * methods by verb and path, through the scope {@link CrudAdvice} opened.
 */
public class RegisterEndpointAdvice {
    private static final String MODULE = "javalin-7";
    private static final String HANDLE_DESCRIPTOR = "(Lio/javalin/http/Context;)V";
    private static final String ITEM_DESCRIPTOR = "(Lio/javalin/http/Context;Ljava/lang/String;)V";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object parsed, @Advice.Argument(0) Endpoint endpoint) {
        try {
            HandlerType type = endpoint.method;
            if (!type.isHttpMethod()) return;
            String verb = type.name();
            String path = endpoint.path;
            Handler handler = endpoint.handler;
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            OtherlodeEndpoints.RegistrationScope scope = OtherlodeEndpoints.registrationScope();
            boolean crudJoined = false;
            if (scope != null) {
                String crudMethod = null;
                boolean takesId = false;
                if ("GET".equals(verb) && path.equals(scope.itemPath)) {
                    crudMethod = "getOne";
                    takesId = true;
                } else if ("GET".equals(verb) && path.equals(scope.collectionPath)) {
                    crudMethod = "getAll";
                } else if ("POST".equals(verb) && path.equals(scope.collectionPath)) {
                    crudMethod = "create";
                } else if ("PATCH".equals(verb) && path.equals(scope.itemPath)) {
                    crudMethod = "update";
                    takesId = true;
                } else if ("DELETE".equals(verb) && path.equals(scope.itemPath)) {
                    crudMethod = "delete";
                    takesId = true;
                }
                crudJoined = crudMethod != null;
                if (crudMethod != null && scope.owner != null && !scope.owner.getClass().isHidden()) {
                    try {
                        Method method = takesId
                                ? scope.owner.getClass().getMethod(crudMethod, Context.class, String.class)
                                : scope.owner.getClass().getMethod(crudMethod, Context.class);
                        handlerClass = method.getDeclaringClass().getName();
                        handlerMethod = crudMethod;
                        handlerDescriptor = takesId ? ITEM_DESCRIPTOR : HANDLE_DESCRIPTOR;
                    } catch (NoSuchMethodException e) {
                        handlerClass = null;
                    }
                }
            }
            if (!crudJoined && handler != null) {
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
                        String declaring = handlerType.getMethod("handle", Context.class).getDeclaringClass().getName();
                        if (!"io.javalin.http.sse.SseHandler".equals(declaring) && !"io.javalin.vue.VueHandler".equals(declaring)) {
                            handlerClass = declaring;
                            handlerMethod = "handle";
                            handlerDescriptor = HANDLE_DESCRIPTOR;
                        }
                    } catch (NoSuchMethodException e) {
                        handlerClass = handlerType.getName();
                    }
                }
            }
            OtherlodeEndpoints.register(MODULE, parsed, verb, path, null, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
