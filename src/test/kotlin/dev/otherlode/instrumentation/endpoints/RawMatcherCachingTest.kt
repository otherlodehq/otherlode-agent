package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawMatcherCachingTest {
    private class CountingModule : EndpointModule {
        val built = AtomicInteger()
        override val name = "counting"

        override fun typeMatcher(): ElementMatcher<in TypeDescription> {
            built.incrementAndGet()
            return named("java.lang.String")
        }

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }

    private fun describe(name: String) = TypeDescription.ForLoadedType.of(Class.forName(name))

    @Test
    fun `the type matcher is built once for each loader, not once for each class`() {
        val module = CountingModule()
        val matcher = EndpointInstrumentation.rawMatcher(module)
        val loaderA = object : ClassLoader(null) {}
        val loaderB = object : ClassLoader(null) {}
        val string = describe("java.lang.String")
        val integer = describe("java.lang.Integer")

        repeat(3) {
            assertTrue(matcher.matches(string, loaderA, null, null, null))
            assertFalse(matcher.matches(integer, loaderA, null, null, null))
        }
        assertEquals(1, module.built.get())

        assertTrue(matcher.matches(string, loaderB, null, null, null))
        assertEquals(2, module.built.get())

        repeat(3) { assertTrue(matcher.matches(string, null, null, null, null)) }
        repeat(3) { assertFalse(matcher.matches(integer, null, null, null, null)) }
        assertEquals(3, module.built.get())
    }
}
