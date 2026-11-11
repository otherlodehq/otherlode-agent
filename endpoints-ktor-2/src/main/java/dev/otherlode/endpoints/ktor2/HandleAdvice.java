package dev.otherlode.endpoints.ktor2;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.ktor.server.routing.HttpMethodRouteSelector;
import io.ktor.server.routing.PathSegmentConstantRouteSelector;
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector;
import io.ktor.server.routing.PathSegmentParameterRouteSelector;
import io.ktor.server.routing.PathSegmentTailcardRouteSelector;
import io.ktor.server.routing.PathSegmentWildcardRouteSelector;
import io.ktor.server.routing.Route;
import io.ktor.server.routing.RouteSelector;
import java.util.ArrayDeque;
import java.util.Deque;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto {@code Route.handle}, Ktor's registration hook. It registers each route node under
 * its own identity, with the verb and path template built from the selectors up to the root.
 * {@link ExecuteResultAdvice} receives the same node at dispatch.
 *
 * <p>Only verb and path selectors contribute. A route with any other selector (headers, content
 * type, query parameters, host, {@code AndRouteSelector}, {@code OrRouteSelector}) merges with any
 * sibling that has the same verb and path.
 *
 * <p>For a suspend lambda the handler join is the lambda's class and {@code invokeSuspend}. For a
 * class that implements the function type by hand it is the class alone. For a hidden class the
 * class, method and descriptor are all null.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class HandleAdvice {
    private static final String MODULE = "ktor-2";
    private static final String INVOKE_SUSPEND_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object node, @Advice.Argument(0) Object body) {
        try {
            Deque<String> segments = new ArrayDeque<>();
            String verb = null;
            Route current = (Route) node;
            while (current != null) {
                RouteSelector selector = current.getSelector();
                if (verb == null && selector instanceof HttpMethodRouteSelector) {
                    verb = ((HttpMethodRouteSelector) selector).getMethod().getValue();
                } else if (selector instanceof PathSegmentConstantRouteSelector) {
                    segments.addFirst(((PathSegmentConstantRouteSelector) selector).getValue());
                } else if (selector instanceof PathSegmentTailcardRouteSelector) {
                    PathSegmentTailcardRouteSelector tailcard = (PathSegmentTailcardRouteSelector) selector;
                    String prefix = tailcard.getPrefix() == null ? "" : tailcard.getPrefix();
                    segments.addFirst(prefix + "{" + tailcard.getName() + "...}");
                } else if (selector instanceof PathSegmentOptionalParameterRouteSelector) {
                    PathSegmentOptionalParameterRouteSelector optional = (PathSegmentOptionalParameterRouteSelector) selector;
                    String prefix = optional.getPrefix() == null ? "" : optional.getPrefix();
                    String suffix = optional.getSuffix() == null ? "" : optional.getSuffix();
                    segments.addFirst(prefix + "{" + optional.getName() + "?}" + suffix);
                } else if (selector instanceof PathSegmentParameterRouteSelector) {
                    PathSegmentParameterRouteSelector parameter = (PathSegmentParameterRouteSelector) selector;
                    String prefix = parameter.getPrefix() == null ? "" : parameter.getPrefix();
                    String suffix = parameter.getSuffix() == null ? "" : parameter.getSuffix();
                    segments.addFirst(prefix + "{" + parameter.getName() + "}" + suffix);
                } else if (selector instanceof PathSegmentWildcardRouteSelector) {
                    segments.addFirst("*");
                }
                current = current.getParent();
            }
            String verbName = verb == null ? "*" : verb;
            String template = "/" + String.join("/", segments);
            String handlerClass = null;
            String handlerMethod = null;
            String handlerDescriptor = null;
            if (body != null) {
                Class<?> bodyType = body.getClass();
                if (!bodyType.isHidden()) {
                    try {
                        bodyType.getDeclaredMethod("invokeSuspend", Object.class);
                        handlerClass = bodyType.getName();
                        handlerMethod = "invokeSuspend";
                        handlerDescriptor = INVOKE_SUSPEND_DESCRIPTOR;
                    } catch (NoSuchMethodException e) {
                        handlerClass = bodyType.getName();
                    }
                }
            }
            OtherlodeEndpoints.register(MODULE, node, verbName, template, null, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
