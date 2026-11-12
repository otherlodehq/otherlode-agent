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

internal const val UNSHADED_EXTRACTOR_NAME = "io.opentelemetry.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

internal const val SHADED_EXTRACTOR_NAME =
    "io.opentelemetry.javaagent.shaded.instrumentation.api.semconv.http.HttpServerAttributesExtractor"

private const val ADVICE_CLASS = "dev.otherlode.endpoints.otelbridge.OnEndAdvice"
private const val PREFIX_ADVICE_CLASS = "dev.otherlode.endpoints.otelbridge.RoutePrefixAdvice"

private const val SPRING_ROUTE_SUPPORT_SIMPLE_NAME = "HttpRouteSupport"

internal val PREFIXER_NAMES: List<String> =
    listOf(
        "io.opentelemetry.javaagent.bootstrap.servlet.ServletContextPath",
        "io.opentelemetry.javaagent.bootstrap.jaxrs.JaxrsContextPath",
        "io.opentelemetry.instrumentation.spring.webmvc.v5_3.HttpRouteSupport",
        "io.opentelemetry.instrumentation.spring.webmvc.v6_0.HttpRouteSupport",
    )

/**
 * Route bridge endpoint module. Counts the route recorded in OpenTelemetry's `HttpRouteState` for a
 * request. It discovers endpoints by dispatch only and never declares one.
 */
class OtelBridgeModule : EndpointModule {
    override val name: String = "otel"

    override val typeNames: Set<String> = setOf(UNSHADED_EXTRACTOR_NAME, SHADED_EXTRACTOR_NAME) + PREFIXER_NAMES

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(*typeNames.toTypedArray())

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
