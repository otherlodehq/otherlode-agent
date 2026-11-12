package dev.otherlode.instrumentation.endpoints.ktor3

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val ROUTING_NODE = "io.ktor.server.routing.RoutingNode"
private const val ROUTING_ROOT = "io.ktor.server.routing.RoutingRoot"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.ktor3"

/** Endpoint module for Ktor 3 (`ktor-3`), covering the `io.ktor:ktor-server-core-jvm` 3.x line. */
class Ktor3Module : EndpointModule {
    override val name: String = "ktor-3"

    override val typeNames: Set<String> = setOf(ROUTING_NODE, ROUTING_ROOT)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(*typeNames.toTypedArray())

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            ROUTING_NODE -> {
                builder.visit(
                    advice.hook("$ADVICE_PACKAGE.HandleAdvice", named<MethodDescription>("handle").and(takesArguments(1))),
                )
            }

            ROUTING_ROOT -> {
                builder.visit(
                    advice.hook("$ADVICE_PACKAGE.ExecuteResultAdvice", named<MethodDescription>("executeResult")),
                )
            }

            else -> {
                builder
            }
        }
}
