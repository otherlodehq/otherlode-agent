package dev.otherlode.instrumentation.endpoints.ktor2

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.isInterface
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val ROUTE = "io.ktor.server.routing.Route"
private const val ROUTING = "io.ktor.server.routing.Routing"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.ktor2"

/** Endpoint module for Ktor 2 (`ktor-2`), covering the `io.ktor:ktor-server-core-jvm` 2.x line. */
class Ktor2Module : EndpointModule {
    override val name: String = "ktor-2"

    override val typeNames: Set<String> = setOf(ROUTE, ROUTING)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> =
        namedOneOf<TypeDescription>(*typeNames.toTypedArray()).and(not(isInterface()))

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            ROUTE -> {
                builder.visit(
                    advice.hook("$ADVICE_PACKAGE.HandleAdvice", named<MethodDescription>("handle").and(takesArguments(1))),
                )
            }

            ROUTING -> {
                builder.visit(
                    advice.hook("$ADVICE_PACKAGE.ExecuteResultAdvice", named<MethodDescription>("executeResult")),
                )
            }

            else -> {
                builder
            }
        }
}
