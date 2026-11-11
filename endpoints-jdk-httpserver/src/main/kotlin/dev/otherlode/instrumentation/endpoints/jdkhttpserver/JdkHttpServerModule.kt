package dev.otherlode.instrumentation.endpoints.jdkhttpserver

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val SERVER_IMPL = "sun.net.httpserver.ServerImpl"
private const val HTTP_CONTEXT_IMPL = "sun.net.httpserver.HttpContextImpl"
private const val CONTEXT_LIST = "sun.net.httpserver.ContextList"
private const val ADVICE_PACKAGE = "dev.otherlode.endpoints.jdkhttpserver"

/**
 * Endpoint module for the JDK's own `com.sun.net.httpserver.HttpServer`. It hooks
 * `sun.net.httpserver.ServerImpl` and two package-private types it works through, never
 * `HttpServer` itself:
 *
 * - `ServerImpl.createContext` (both overloads) is the registration hook.
 * - `HttpContextImpl.setHandler` gives a context created without a handler its handler.
 * - `ContextList.findContext(String protocol, String path)`, the two-argument overload, is the
 *   dispatch point. A null return is the framework's own 404 path, which counts nothing. The
 *   three-argument overload backs `contains` and `remove` and is left alone.
 *
 * A context path is a prefix match with no verb constraint, so every endpoint this module
 * declares has verb `"*"` and a verbatim template equal to `HttpContext.getPath()` as registered.
 */
class JdkHttpServerModule : EndpointModule {
    override val name: String = "jdk-httpserver"

    override val bootModulesNeedingSeamRead: Set<String> = setOf("jdk.httpserver")

    override val handlerInterfaces: Set<String> = setOf("com.sun.net.httpserver.HttpHandler")

    override val typeNames: Set<String> = setOf(SERVER_IMPL, HTTP_CONTEXT_IMPL, CONTEXT_LIST)

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(*typeNames.toTypedArray())

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            SERVER_IMPL -> {
                builder.visit(advice.hook("$ADVICE_PACKAGE.CreateContextAdvice", named("createContext")))
            }

            HTTP_CONTEXT_IMPL -> {
                builder.visit(advice.hook("$ADVICE_PACKAGE.SetHandlerAdvice", named("setHandler")))
            }

            CONTEXT_LIST -> {
                builder.visit(
                    advice.hook("$ADVICE_PACKAGE.FindContextAdvice", named<MethodDescription>("findContext").and(takesArguments(2))),
                )
            }

            else -> {
                builder
            }
        }
}
