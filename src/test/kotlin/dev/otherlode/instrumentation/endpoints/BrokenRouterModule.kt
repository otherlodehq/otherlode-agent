package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named

class BrokenRouterModule : EndpointModule {
    override val name: String = "broken-router"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.BrokenRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> = builder.visit(advice.hook("dev.otherlode.endpoints.fake.BrokenRouterInvokeAdvice", named("invoke")))
}
