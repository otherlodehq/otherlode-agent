package dev.otherlode.instrumentation.endpoints.javalin7

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.declaresField
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
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.javalin7"

/** Javalin 7's `ParsedEndpoint` exposes its endpoint as a public field; Javalin 6 reads it through `getEndpoint()`. */
private val JAVALIN_7_PARSED_ENDPOINT: ElementMatcher<TypeDescription> =
    declaresField(named<FieldDescription>("endpoint").and(isPublic()))

private fun parsedEndpointIsJavalin7(classLoader: ClassLoader): Boolean =
    try {
        val parsedEndpoint = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(classLoader)).describe(PARSED_ENDPOINT)
        parsedEndpoint.isResolved && JAVALIN_7_PARSED_ENDPOINT.matches(parsedEndpoint.resolve())
    } catch (e: Exception) {
        false
    }

/**
 * Endpoint module for Javalin 7 (`javalin-7`), compiled against 7.0.0.
 *
 * Javalin 6 keeps every hooked descriptor, so a module matching by name would weave a release it
 * was not built for and fail on it. This one accepts `ParsedEndpoint` only when it declares a
 * public `endpoint` field, and `ApiBuilder` only on a class loader whose `ParsedEndpoint` has
 * that shape.
 *
 * The exit of the `ParsedEndpoint` constructor declares an endpoint, after the path parsed. The
 * entry of `ParsedEndpoint.handle` counts it, which Javalin's HTTP lifecycle task reaches once
 * per request for the first match, after the before-handlers: a request one of them rejects,
 * redirects or skips is not counted. The match itself is inlined and compiler-generated, so
 * nothing earlier can be hooked. `handleWithPathParams`, which `handle` calls, is internal and
 * exists only in some 7.x releases, so it is not hooked.
 *
 * `ApiBuilder.crud` registers a `CrudHandler`'s five endpoints through lambdas of Javalin's own.
 * Its entry and exit open and close a registration scope in the bootstrap seam, which the
 * registration advice reads to join each endpoint to the handler's method.
 */
class Javalin7Module : EndpointModule {
    override val name: String = "javalin-7"

    override val handlerInterfaces: Set<String> = setOf(HANDLER)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named<TypeDescription>(PARSED_ENDPOINT).and(JAVALIN_7_PARSED_ENDPOINT)

    override fun typeMatcher(classLoader: ClassLoader?): ElementMatcher<in TypeDescription> {
        val loader = WeakReference(classLoader)
        val apiBuilderIsJavalin7 = lazy(LazyThreadSafetyMode.PUBLICATION) { loader.get()?.let(::parsedEndpointIsJavalin7) == true }
        val parsedEndpoint = typeMatcher()
        return ElementMatcher { type ->
            when (type.name) {
                PARSED_ENDPOINT -> parsedEndpoint.matches(type)
                API_BUILDER -> apiBuilderIsJavalin7.value
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
