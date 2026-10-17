package dev.otherlode.instrumentation.endpoints.javalin6

import dev.otherlode.instrumentation.endpoints.javalin7.Javalin7Module
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.StubMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which classes [Javalin6Module] accepts, and that it leaves Javalin 7's alone. Each type is
 * described without loading it.
 */
class Javalin6ModuleMatcherTest {
    @Test
    fun `ParsedEndpoint is accepted only with a public getEndpoint`() {
        val javalin6Shape =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.javalin.router.ParsedEndpoint")
                .defineField("endpoint", Any::class.java, Visibility.PRIVATE)
                .defineMethod("getEndpoint", Any::class.java, Visibility.PUBLIC)
                .intercept(StubMethod.INSTANCE)
                .make()
                .typeDescription
        val javalin7Shape =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.javalin.router.ParsedEndpoint")
                .defineField("endpoint", Any::class.java, Visibility.PUBLIC)
                .make()
                .typeDescription
        val neither =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.javalin.router.ParsedEndpoint")
                .make()
                .typeDescription

        assertTrue(Javalin6Module().typeMatcher().matches(javalin6Shape))
        assertFalse(Javalin7Module().typeMatcher().matches(javalin6Shape))
        assertFalse(Javalin6Module().typeMatcher().matches(javalin7Shape))
        assertTrue(Javalin7Module().typeMatcher().matches(javalin7Shape))
        assertFalse(Javalin6Module().typeMatcher().matches(neither))
        assertFalse(Javalin7Module().typeMatcher().matches(neither))
    }

    @Test
    fun `a type of another name is not accepted`() {
        val other =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.javalin.router.Endpoint")
                .make()
                .typeDescription

        assertFalse(Javalin6Module().typeMatcher().matches(other))
        assertFalse(Javalin6Module().typeMatcher(javaClass.classLoader).matches(other))
    }

    @Test
    fun `ApiBuilder is accepted only on a class loader whose ParsedEndpoint is Javalin 6's`() {
        val apiBuilder: TypeDescription = TypeDescription.Latent("io.javalin.apibuilder.ApiBuilder", 0, null)

        assertTrue(Javalin6Module().typeMatcher(javaClass.classLoader).matches(apiBuilder))
        assertFalse(Javalin7Module().typeMatcher(javaClass.classLoader).matches(apiBuilder))
        assertFalse(Javalin6Module().typeMatcher(null).matches(apiBuilder), "the bootstrap loader has no Javalin")
        assertFalse(Javalin6Module().typeMatcher().matches(apiBuilder), "without a loader the module cannot tell the release")
    }
}
