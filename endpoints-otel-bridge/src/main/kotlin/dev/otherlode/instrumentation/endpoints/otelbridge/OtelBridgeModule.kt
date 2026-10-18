package dev.otherlode.instrumentation.endpoints.otelbridge

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

/** The unshaded class name a plain `opentelemetry-instrumentation-api` dependency loads under. */
internal const val UNSHADED_EXTRACTOR_NAME = "io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

/** The same class's name inside the OpenTelemetry Java agent's own shaded jar, verified against a real 2.31.1 agent jar. */
internal const val SHADED_EXTRACTOR_NAME =
    "io.opentelemetry.javaagent.shaded.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

private const val ADVICE_CLASS = "dev.otherlode.endpoints.otelbridge.OnEndAdvice"
private const val PREFIX_ADVICE_CLASS = "dev.otherlode.endpoints.otelbridge.RoutePrefixAdvice"

private const val SPRING_ROUTE_SUPPORT_SIMPLE_NAME = "HttpRouteSupport"

/**
 * The classes holding OpenTelemetry's route prefixers: the Java agent's servlet and JAX-RS
 * bootstrap helpers, and the Spring MVC library instrumentation's route support for each servlet
 * namespace. A deployment loads only some of them.
 */
internal val PREFIXER_NAMES: List<String> =
    listOf(
        "io.opentelemetry.javaagent.bootstrap.servlet.ServletContextPath",
        "io.opentelemetry.javaagent.bootstrap.jaxrs.JaxrsContextPath",
        "io.opentelemetry.instrumentation.spring.webmvc.v5_3.HttpRouteSupport",
        "io.opentelemetry.instrumentation.spring.webmvc.v6_0.HttpRouteSupport",
    )

/**
 * Route bridge endpoint module. Counts the route OpenTelemetry's own HTTP server instrumentation
 * resolved for a request, for a framework this project has no dedicated endpoint module for. It
 * only ever discovers endpoints by dispatch and never declares one, since OpenTelemetry learns a
 * route only when a request ends on it.
 *
 * Matches both the unshaded `opentelemetry-instrumentation-api` artifact's own
 * `HttpServerAttributesExtractor` and the OpenTelemetry Java agent's relocated copy of the same
 * class, and weaves [OnEndAdvice] onto the exit of `onEnd`. [OnEndAdvice] is written once against
 * the unshaded names; for the relocated copy, [AdviceBinder] rewrites every `io/opentelemetry/...`
 * reference in its bytecode to the agent's `io/opentelemetry/javaagent/shaded/...` prefix before
 * binding, so one advice class serves both deployments.
 *
 * It also weaves [RoutePrefixAdvice] onto the exit of each of OpenTelemetry's route prefixers it
 * finds loaded, which records the unprefixed route a prefixed one was built from. [OnEndAdvice]
 * resolves the final route back through that table, so the application root and servlet path
 * OpenTelemetry adds do not make a second endpoint of a route a framework module already counts.
 * A deployment without a prefixer, or with one this module has not read, is unaffected: the route
 * stays as OpenTelemetry reported it.
 *
 * Off by default. It is always discovered, and [dev.otherlode.Agent] drops it unless
 * [dev.otherlode.config.AgentConfig.otelBridgeEnabled] is set.
 */
class OtelBridgeModule : EndpointModule {
    override val name: String = "otel"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        namedOneOf(UNSHADED_EXTRACTOR_NAME, SHADED_EXTRACTOR_NAME, *PREFIXER_NAMES.toTypedArray())

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        if (typeDescription.name in PREFIXER_NAMES) {
            val prefixer = if (typeDescription.simpleName == SPRING_ROUTE_SUPPORT_SIMPLE_NAME) "prependContextPath" else "prepend"
            val method = named<MethodDescription>(prefixer).and(takesArguments(2)).and(isStatic())
            return builder.visit(advice.hook(PREFIX_ADVICE_CLASS, method))
        }
        val onEnd = named<MethodDescription>("onEnd").and(takesArguments(5))
        return builder.visit(advice.hook(ADVICE_CLASS, onEnd, remapPrefixesFor(typeDescription.name)))
    }
}

/**
 * The `io/opentelemetry/...` internal-name prefix rewrite [OnEndAdvice] needs to weave onto
 * [typeName], keyed only on whether [typeName] is the shaded or the unshaded extractor name.
 * Empty for the unshaded name, since [OnEndAdvice] is already written against it directly.
 *
 * [AdviceBinder.hook] tries prefixes longest first, so the order these are written in here is for
 * readability only.
 *
 * `internal` rather than `private` so [OtelBridgeModuleTest] can pin the exact rewrite this
 * module asks for, directly, without needing the real relocated OpenTelemetry javaagent jar on
 * the test classpath to prove `transform` chose the right prefixes for the shaded name.
 */
internal fun remapPrefixesFor(typeName: String): Map<String, String> =
    if (typeName == SHADED_EXTRACTOR_NAME) {
        mapOf(
            "io/opentelemetry/instrumentation/api/" to "io/opentelemetry/javaagent/shaded/instrumentation/api/",
            "io/opentelemetry/context/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/context/",
            "io/opentelemetry/api/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/api/",
        )
    } else {
        emptyMap()
    }
