package dev.otherlode.endpoints.javalin6;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of the {@code ParsedEndpoint(Endpoint, RouterConfig)} constructor, which
 * every verb method, {@code addEndpoint}, {@code ApiBuilder} and Javalin's own plugins reach
 * through {@code InternalRouter.addHttpEndpoint}, and which runs only after the path parsed. The
 * instance is the dispatch key. Only an endpoint whose method {@code isHttpMethod()} is declared:
 * before, after, beforeMatched, afterMatched and the WebSocket upgrade handlers are not endpoints.
 * A WebSocket route never builds a {@code ParsedEndpoint}.
 *
 * <p>The handler join names {@code handle} and the class declaring it for a named class, and the
 * method the lambda calls for a hidden one. {@code SseHandler} and {@code VueHandler} are
 * Javalin's own wrappers, so an endpoint whose {@code handle} they declare gets no join. While
 * {@code ApiBuilder.crud} runs on this thread, {@link CrudAdvice} has opened a registration scope
 * and the five endpoints it registers through lambdas join to the {@code CrudHandler}'s methods
 * by verb and path. Any other registration inside the scope keeps its own join.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class RegisterEndpointAdvice {
    private static final String MODULE = "javalin-6";
    private static final String HANDLE_DESCRIPTOR = "(Lio/javalin/http/Context;)V";
    private static final String ITEM_DESCRIPTOR = "(Lio/javalin/http/Context;Ljava/lang/String;)V";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object parsed, @Advice.Argument(0) Endpoint endpoint) {
        try {
            HandlerType type = endpoint.getMethod();
            if (!type.isHttpMethod()) return;
            String verb = type.name();
            String path = endpoint.getPath();
            Handler handler = endpoint.getHandler();
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
