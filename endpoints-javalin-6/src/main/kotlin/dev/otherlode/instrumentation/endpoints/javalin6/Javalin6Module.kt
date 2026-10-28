package dev.otherlode.instrumentation.endpoints.javalin6

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresMethod
import net.bytebuddy.matcher.ElementMatchers.isConstructor
import net.bytebuddy.matcher.ElementMatchers.isPublic
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.pool.TypePool
import java.lang.ref.WeakReference

private const val PARSED_ENDPOINT = "io.javalin.router.ParsedEndpoint"
private const val API_BUILDER = "io.javalin.apibuilder.ApiBuilder"
private const val SERVLET_CONTEXT = "io.javalin.http.servlet.JavalinServletContext"
private const val CRUD_HANDLER = "io.javalin.apibuilder.CrudHandler"
private const val HANDLER = "io.javalin.http.Handler"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.javalin6"

/** Javalin 6's `ParsedEndpoint` reads its endpoint through a public `getEndpoint()`; Javalin 7 exposes a field. */
private val JAVALIN_6_PARSED_ENDPOINT: ElementMatcher<TypeDescription> =
    declaresMethod(named<MethodDescription>("getEndpoint").and(takesArguments(0)).and(isPublic()))

private fun parsedEndpointIsJavalin6(classLoader: ClassLoader): Boolean =
    try {
        val parsedEndpoint = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(classLoader)).describe(PARSED_ENDPOINT)
        parsedEndpoint.isResolved && JAVALIN_6_PARSED_ENDPOINT.matches(parsedEndpoint.resolve())
    } catch (e: Exception) {
        false
    }

/**
 * Endpoint module for Javalin 6 (`javalin-6`), compiled against 6.1.0. Javalin 6.0.0 and 6.0.1
 * match the same classes and have no public `Endpoint.getHandler()`, so the module switches itself
 * off there with a `LINKAGE_ERROR` at the first registration.
 *
 * Javalin 7 keeps every hooked descriptor, so a module matching by name would weave a release it
 * was not built for and fail on it. This one accepts `ParsedEndpoint` only when it declares
 * `getEndpoint()`, and `ApiBuilder` only on a class loader whose `ParsedEndpoint` has that shape.
 *
 * The exit of the `ParsedEndpoint` constructor declares an endpoint, after the path parsed. The
 * entry of `ParsedEndpoint.handle` counts it, which Javalin's HTTP lifecycle task reaches once
 * per request for the first match, after the before-handlers: a request one of them rejects,
 * redirects or skips is not counted. The match itself is inlined and compiler-generated, so
 * nothing earlier can be hooked.
 *
 * `ApiBuilder.crud` registers a `CrudHandler`'s five endpoints through lambdas of Javalin's own.
 * Its entry and exit open and close a registration scope in the bootstrap seam, which the
 * registration advice reads to join each endpoint to the handler's method.
 */
class Javalin6Module : EndpointModule {
    override val name: String = "javalin-6"

    override val handlerInterfaces: Set<String> = setOf(HANDLER)

    override val typeNames: Set<String> = setOf(PARSED_ENDPOINT, API_BUILDER)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(PARSED_ENDPOINT).and(JAVALIN_6_PARSED_ENDPOINT)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val apiBuilderIsJavalin6 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::parsedEndpointIsJavalin6) == true }
        val parsedEndpoint = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                !in typeNames -> false
                PARSED_ENDPOINT -> parsedEndpoint.matches(type)
                API_BUILDER -> apiBuilderIsJavalin6.value
                else -> false
            }
        }
    }

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            PARSED_ENDPOINT -> {
                builder
                    .visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.RegisterEndpointAdvice",
                            isConstructor<MethodDescription>().and(takesArguments(2)),
                        ),
                    ).visit(
                        advice.hook(
                            "$ADVICE_PACKAGE.DispatchAdvice",
                            named<MethodDescription>("handle")
                                .and(takesArguments(2))
                                .and(takesArgument(0, named<TypeDescription>(SERVLET_CONTEXT))),
                        ),
                    )
            }

            API_BUILDER -> {
                builder.visit(
                    advice.hook(
                        "$ADVICE_PACKAGE.CrudAdvice",
                        named<MethodDescription>("crud")
                            .and(takesArguments(3))
                            .and(takesArgument(1, named<TypeDescription>(CRUD_HANDLER))),
                    ),
                )
            }

            else -> {
                builder
            }
        }
}
