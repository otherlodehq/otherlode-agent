package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EndpointModuleDefaultsTest {
    private class MinimalModule : EndpointModule {
        override val name: String = "minimal"

        override fun typeMatcher(): ElementMatcher<in TypeDescription> = none()

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }

    @Test
    fun `a module that names no boot module needs no read edge to the seam`() {
        assertEquals(emptySet(), MinimalModule().bootModulesNeedingSeamRead)
    }

    @Test
    fun `a module that names no types can match a class of any name`() {
        assertNull(MinimalModule().typeNames)
    }

    @Test
    fun `a module that names no handler interface asks for no lambda to be recorded`() {
        assertEquals(emptySet(), MinimalModule().handlerInterfaces)
    }

    @Test
    fun `a module that needs nothing before a manifest is built does nothing there`() {
        MinimalModule().beforeManifest(graceMillis = 0, finalFlush = true)
    }

    @Test
    fun `an advice binder resolves against the bootstrap loader when the target loader is null`() {
        val binder = AdviceBinder(javaClass.classLoader, null)

        binder.bind("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice")

        assertFailsWith<TypePool.Resolution.NoSuchTypeException> {
            binder.bind("dev.otherlode.instrumentation.endpoints.api.fixture.NotAnAdvice")
        }
    }
}
