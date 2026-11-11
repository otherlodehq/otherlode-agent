package dev.otherlode.instrumentation.endpoints

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named

class FakeRouterModule : EndpointModule {
    override val name: String = "fake-router"

    override val bootModulesNeedingSeamRead: Set<String> = setOf("java.net.http")

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.FakeRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        builder
            .visit(advice.hook("dev.otherlode.endpoints.fake.FakeRouterAddRouteAdvice", named("addRoute")))
            .visit(advice.hook("dev.otherlode.endpoints.fake.FakeRouterInvokeAdvice", named("invoke")))
            .visit(advice.hook("dev.otherlode.endpoints.fake.FakeRouterPublishAdvice", named("publishRoutes")))

    override fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) {
        beforeManifestCalls += graceMillis to finalFlush
    }

    override fun declare(frameworkObject: Any) {
        val router = frameworkObject.javaClass

        @Suppress("UNCHECKED_CAST")
        val routes = router.getMethod("routes").invoke(frameworkObject) as List<Any>
        for (route in routes) {
            val routeClass = route.javaClass
            val verb = routeClass.getField("verb").get(route) as String
            val path = routeClass.getField("path").get(route) as String
            val handler = routeClass.getField("handler").get(route)
            OtherlodeEndpoints.register("fake-router", "$verb $path", verb, path, null, handler.javaClass.name, null, null)
        }
    }

    companion object {
        val beforeManifestCalls: MutableList<Pair<Long, Boolean>> = java.util.concurrent.CopyOnWriteArrayList()
    }
}
