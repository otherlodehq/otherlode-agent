package dev.otherlode.instrumentation.endpoints.jaxrs

import dev.otherlode.instrumentation.endpoints.api.AdviceBinder
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.pool.TypePool
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// This test generates the resource class in memory, because JUnit's discovery loads every class in the test
// output and the test agent would declare an on-disk one before this test runs.
class JaxRsModuleBindFailureTest {
    private val generatedName = "com.example.jaxrs.generated.BindFailureResource"

    @Test
    fun `a transform that fails at advice binding leaves no endpoint declared`() {
        val loader = javaClass.classLoader
        val unloaded =
            ByteBuddy()
                .subclass(Any::class.java)
                .name(generatedName)
                .annotateType(
                    AnnotationDescription.Builder
                        .ofType(Path::class.java)
                        .define("value", "/bind-failure")
                        .build(),
                ).defineMethod("get", String::class.java, Visibility.PUBLIC)
                .intercept(FixedValue.value("never"))
                .annotateMethod(AnnotationDescription.Builder.ofType(GET::class.java).build())
                .make()
        val locator = ClassFileLocator.Compound(ClassFileLocator.Simple.of(unloaded), ClassFileLocator.ForClassLoader.of(loader))
        val typeDescription =
            TypePool.Default
                .of(locator)
                .describe(generatedName)
                .resolve()
        val brokenBinder = AdviceBinder(URLClassLoader(arrayOf(), null), URLClassLoader(arrayOf(), null))

        assertFailsWith<IllegalStateException> {
            JaxRsModule().transform(ByteBuddy().redefine<Any>(typeDescription, locator), typeDescription, brokenBinder, loader)
        }

        val declared = JaxRsTestAgent.endpointRegistry.endpoints().filter { it.handlerClass == generatedName }
        assertTrue(declared.isEmpty(), "a class whose transform failed must not be declared, found: $declared")
    }
}
