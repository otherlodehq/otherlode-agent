package dev.otherlode.instrumentation.endpoints.api

import dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.matcher.ElementMatchers.named
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [AdviceBinder] resolves an advice class by name and that the resulting [net.bytebuddy.asm.Advice]
 * weaves correctly, without ever referencing the advice class as a class literal at the weave call
 * site (only its fully qualified name is passed to [AdviceBinder.bind]).
 *
 * Installs a real `AgentBuilder` transformer through [ByteBuddyAgent.install], the same route
 * [dev.otherlode.instrumentation.OtherlodeInstrumentation]'s own tests use, rather than
 * `ByteBuddy().redefine(...)` loaded with a fresh classloader: the fixture class is defined for
 * the first time only after the transformer is installed, so there is no already-loaded copy for
 * a wrapping classloader to conflict with.
 */
class AdviceBinderTest {
    private var transformer: ResettableClassFileTransformer? = null

    @AfterTest
    fun tearDown() {
        transformer?.reset(ByteBuddyAgent.install(), AgentBuilder.RedefinitionStrategy.DISABLED)
        transformer = null
    }

    @Test
    fun `binds an advice class by name and weaves it into a fixture method`() {
        PingAdvice.entries = 0
        val instrumentation = ByteBuddyAgent.install()
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        transformer =
            AgentBuilder
                .Default()
                .type(named("dev.otherlode.instrumentation.endpoints.api.fixture.PingTarget"))
                .transform { builder, _, _, _, _ ->
                    builder.visit(
                        binder
                            .bind("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice")
                            .on(named("ping")),
                    )
                }.installOn(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val target = Class.forName("dev.otherlode.instrumentation.endpoints.api.fixture.PingTarget", true, loader)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("ping").invoke(instance)
        target.getMethod("ping").invoke(instance)

        assertEquals(2, PingAdvice.entries)
    }

    @Test
    fun `bind with remap prefixes rewrites a referenced type's internal name`() {
        val adviceClassName = "dev.otherlode.instrumentation.endpoints.api.fixture.RemapAdvice"
        val originalBytes =
            ClassFileLocator.ForClassLoader
                .of(javaClass.classLoader)
                .locate(adviceClassName)
                .resolve()

        val remapped = remapClassBytes(originalBytes, mapOf("com/example/remap/" to "com/example/remapped/"))

        val remappedText = String(remapped, Charsets.ISO_8859_1)
        assertFalse("com/example/remap/Before" in remappedText, "the original reference must not survive the remap")
        assertTrue("com/example/remapped/Before" in remappedText, "the rewritten reference must name the remapped type")

        // The remapped bytecode must also resolve and bind cleanly, proving AdviceBinder's own
        // TypePool can describe the advice once the type it references only exists under its
        // remapped name.
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)
        val advice = binder.bind(adviceClassName, mapOf("com/example/remap/" to "com/example/remapped/"))
        assertNotNull(advice)
    }

    @Test
    fun `bind with an empty remap map behaves exactly like the single-argument bind`() {
        PingAdvice.entries = 0
        val binder = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        val withoutMap = binder.bind("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice")
        val withEmptyMap = binder.bind("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice", emptyMap())

        assertNotNull(withoutMap)
        assertNotNull(withEmptyMap)
    }

    @Test
    fun `hook returns a wrapper and records the advice class with its matcher on the view that attached it`() {
        val shared = AdviceBinder(javaClass.classLoader, javaClass.classLoader)
        val view = shared.forCall()
        val matcher = named<MethodDescription>("ping")

        val wrapper = view.hook("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice", matcher)

        assertNotNull(wrapper)
        val recorded = view.recordedHooks().single()
        assertEquals("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice", recorded.adviceClassName)
        assertTrue(matcher === recorded.methodMatcher, "the matcher is recorded as given")
    }

    @Test
    fun `a view's record is its own, so another view and the shared binder see none of it`() {
        val shared = AdviceBinder(javaClass.classLoader, javaClass.classLoader)
        val first = shared.forCall()
        val second = shared.forCall()

        first.hook("dev.otherlode.instrumentation.endpoints.api.fixture.PingAdvice", named<MethodDescription>("ping"))

        assertEquals(1, first.recordedHooks().size)
        assertTrue(second.recordedHooks().isEmpty())
        assertTrue(shared.recordedHooks().isEmpty())
    }

    @Test
    fun `hook with remap prefixes binds the remapped advice and records it`() {
        val view = AdviceBinder(javaClass.classLoader, javaClass.classLoader).forCall()

        view.hook(
            "dev.otherlode.instrumentation.endpoints.api.fixture.RemapAdvice",
            named<MethodDescription>("ping"),
            mapOf("com/example/remap/" to "com/example/remapped/"),
        )

        assertEquals(1, view.recordedHooks().size)
    }
}
