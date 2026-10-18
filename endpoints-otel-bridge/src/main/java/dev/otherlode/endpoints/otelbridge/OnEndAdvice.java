package dev.otherlode.endpoints.otelbridge;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.HttpRouteState;
import java.util.List;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code HttpServerAttributesExtractor.onEnd}, the one point OpenTelemetry's
 * own HTTP server instrumentation calls once per server span, after every {@code
 * HttpServerRoute.update} source-priority call has already settled the final route. Span end,
 * not {@code HttpServerRoute.update} itself, is the read point because {@code update} fires
 * several times per request as higher-priority sources override lower ones, so no single call to
 * it knows the eventual winner.
 *
 * <p>Compiled against the unshaded {@code opentelemetry-instrumentation-api} artifact, verified at
 * its oldest supported 2.x release, 2.0.0. {@link
 * dev.otherlode.instrumentation.endpoints.otelbridge.OtelBridgeModule} binds this
 * same class a second way for the OpenTelemetry Java agent's own relocated copy of {@code
 * HttpServerAttributesExtractor}, with every {@code io/opentelemetry/...} reference in this
 * class's bytecode rewritten to that agent's {@code io/opentelemetry/javaagent/shaded/...} prefix
 * before binding, so one advice class serves both deployments.
 *
 * <p>The route is first resolved through the table {@link RoutePrefixAdvice} fills, which takes
 * off the application root and servlet path OpenTelemetry put in front of it, so a request a
 * framework module counted under its own template is not counted again under the prefixed one. A
 * route no recorded prefixer built is used as it is.
 *
 * <p>A Vert.x Web route reaches OpenTelemetry spelled {@code /users/:id}. A segment that is
 * {@code :name}, or {@code :name::type} as Vert.x Web 5.2 writes a typed parameter, becomes
 * {@code {name}} before anything is looked up, so a request the {@code vertx-web} module counted
 * under {@code /users/{id}} is not counted again. A colon anywhere else in a segment stays. The name
 * pattern follows Vert.x's {@code io.vertx.web.route.param.extended-pattern} property.
 *
 * <p>A route this bridge reads for an identity another endpoint module already owns is left
 * alone: {@link OtherlodeEndpoints#lookup} misses for a key no framework module ever bound, and {@link
 * OtherlodeEndpoints#recordDispatchIfUnowned} then refuses to bind it either, since an entry for that
 * identity already exists under a different framework name. The request goes uncounted here, so
 * a request the owning module counted is never counted twice. A request the owner did not count,
 * because it disabled itself or because a framework with no module of its own served the same
 * identity, is lost; that is accepted. A key {@code recordDispatchIfUnowned} has already refused
 * stays unbound, so the same identity repeats this one map miss and one refusal on every later
 * request for it; that repeated cost is small and accepted rather than adding a sentinel binding to
 * skip it.
 */
public class OnEndAdvice {
    private static final String MODULE = "otel";

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(1) Context context) {
        try {
            HttpRouteState state = HttpRouteState.fromContextOrNull(context);
            if (state == null) return;
            String route = state.getRoute();
            if (route == null) return;
            route = OtherlodeEndpoints.resolveRoutePrefix(route);
            if (route.indexOf("/:") >= 0) {
                boolean extended = Boolean.getBoolean("io.vertx.web.route.param.extended-pattern");
                StringBuilder converted = new StringBuilder(route.length() + 4);
                int length = route.length();
                int start = 0;
                while (start <= length) {
                    int end = route.indexOf('/', start);
                    if (end < 0) end = length;
                    String segment = route.substring(start, end);
                    String name = null;
                    if (segment.length() > 1 && segment.charAt(0) == ':') {
                        int nameEnd = 1;
                        while (nameEnd < segment.length()) {
                            char c = segment.charAt(nameEnd);
                            boolean nameChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                                    || c == '_' || (extended && (c == '$' || c == '-'));
                            if (!nameChar) break;
                            nameEnd++;
                        }
                        boolean typed = segment.startsWith("::", nameEnd);
                        int typeEnd = typed ? nameEnd + 2 : nameEnd;
                        while (typed && typeEnd < segment.length()) {
                            char c = segment.charAt(typeEnd);
                            boolean nameChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                                    || c == '_' || (extended && (c == '$' || c == '-'));
                            if (!nameChar) break;
                            typeEnd++;
                        }
                        if (nameEnd > 1 && typeEnd == segment.length() && (!typed || typeEnd > nameEnd + 2)) {
                            name = segment.substring(1, nameEnd);
                        }
                    }
                    converted.append(name != null ? "{" + name + "}" : segment);
                    if (end < length) converted.append('/');
                    start = end + 1;
                }
                route = converted.toString();
            }
            String method = state.getMethod();
            String verb = method == null ? "*" : method;
            List<String> key = List.of(verb, route);
            Object entry = OtherlodeEndpoints.lookup(MODULE, key);
            if (entry == null) {
                entry = OtherlodeEndpoints.recordDispatchIfUnowned(MODULE, key, verb, route, null, null);
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
