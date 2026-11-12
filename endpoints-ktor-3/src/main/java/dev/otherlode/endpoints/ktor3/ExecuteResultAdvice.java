package dev.otherlode.endpoints.ktor3;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.ktor.server.routing.HttpMethodRouteSelector;
import io.ktor.server.routing.PathSegmentConstantRouteSelector;
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector;
import io.ktor.server.routing.PathSegmentParameterRouteSelector;
import io.ktor.server.routing.PathSegmentTailcardRouteSelector;
import io.ktor.server.routing.PathSegmentWildcardRouteSelector;
import io.ktor.server.routing.RouteSelector;
import io.ktor.server.routing.RoutingNode;
import java.util.ArrayDeque;
import java.util.Deque;
import net.bytebuddy.asm.Advice;

/**
 * Runs on entry to {@code RoutingRoot.executeResult}, Ktor's dispatch point. It records one hit
 * per matched request. When {@link HandleAdvice} never registered the node, the advice builds the
 * verb and path template from the selectors up to the root and records the endpoint as discovered
 * by dispatch.
 */
public class ExecuteResultAdvice {
    private static final String MODULE = "ktor-3";

    // Advice inlines only this method's bytecode, so a helper call here would stay a real call that
    // the target loader cannot resolve. Keep the body flat.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(1) Object route) {
        try {
            // A suspend function re-enters from its continuation with null arguments; only the
            // first entry is a dispatch.
            if (route == null) return;
            Object entry = OtherlodeEndpoints.lookup(MODULE, route);
            if (entry == null) {
                Deque<String> segments = new ArrayDeque<>();
                String verb = null;
                RoutingNode current = (RoutingNode) route;
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
                entry = OtherlodeEndpoints.recordDispatch(MODULE, route, verbName, template, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
