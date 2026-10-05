package dev.otherlode.instrumentation

import net.bytebuddy.dynamic.ClassFileLocator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdkTypePoolTest {
    private class CountingLocator(
        private val delegate: ClassFileLocator,
    ) : ClassFileLocator {
        val located = CopyOnWriteArrayList<String>()

        override fun locate(name: String): ClassFileLocator.Resolution {
            located += name
            return delegate.locate(name)
        }

        override fun close() = Unit
    }

    private fun counting() = CountingLocator(ClassFileLocator.ForClassLoader.of(ClassLoader.getPlatformClassLoader()))

    @Test
    fun `two transforms describing String parse its class file once`() {
        val locator = counting()
        val shared = JdkTypePool(locator)
        val strategy = PlaceholderPoolStrategy(shared)

        repeat(2) {
            val pool = strategy.typePool(ClassFileLocator.NoOp.INSTANCE, null)
            val string = pool.describe("java.lang.String").resolve()
            assertEquals("java.lang.String", string.name)
            assertTrue(string.declaredMethods.isNotEmpty())
            strategy.release()
        }

        assertEquals(1, locator.located.count { it == "java.lang.String" })
    }

    @Test
    fun `a name outside java is never looked up and never cached`() {
        val locator = counting()
        val shared = JdkTypePool(locator)

        assertFalse(shared.describe("javax.swing.JPanel").isResolved)
        assertFalse(shared.describe("com.example.Thing").isResolved)
        assertFalse(shared.describe("[Lcom.example.Thing;").isResolved)

        assertEquals(emptyList(), locator.located.toList())
        assertEquals(0, shared.cachedTypes())
    }

    @Test
    fun `an array of a java type resolves and a java name nobody provides is not cached`() {
        val locator = counting()
        val shared = JdkTypePool(locator)

        assertTrue(shared.describe("[Ljava.lang.String;").isResolved)
        assertFalse(shared.describe("java.nothing.There").isResolved)
        assertFalse(shared.describe("java.nothing.There").isResolved)

        assertEquals(1, shared.cachedTypes())
        assertEquals(2, locator.located.count { it == "java.nothing.There" })
    }

    @Test
    fun `the cache stays within its bound and keeps the most recently used type`() {
        val locator = counting()
        val shared = JdkTypePool(locator, maxTypes = 3)
        val names = listOf("java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short")

        names.take(3).forEach { shared.describe(it).resolve().modifiers }
        shared.describe("java.lang.String").resolve().modifiers
        shared.describe("java.lang.Short").resolve().modifiers
        assertEquals(3, shared.cachedTypes())

        val before = locator.located.size
        shared.describe("java.lang.String").resolve().modifiers
        assertEquals(before, locator.located.size)
        shared.describe("java.lang.Integer").resolve().modifiers
        assertEquals(before + 1, locator.located.size)
        assertEquals(3, shared.cachedTypes())
    }

    @Test
    fun `a placeholder pool with the shared parent still substitutes a missing type and never a java one`() {
        val shared = JdkTypePool()
        val strategy = PlaceholderPoolStrategy(shared)
        val pool = strategy.typePool(ClassFileLocator.NoOp.INSTANCE, null)

        pool.describe("java.util.List").resolve().modifiers
        pool.describe("com.example.absent.Missing").resolve().modifiers

        assertEquals(setOf("com.example.absent.Missing"), strategy.substituted())
        strategy.release()
    }

    @Test
    fun `concurrent describes resolve the same types without error`() {
        val locator = counting()
        val shared = JdkTypePool(locator)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures =
                List(8) {
                    executor.submit {
                        start.await()
                        repeat(50) {
                            listOf("java.lang.String", "java.util.Map", "java.io.File").forEach { name ->
                                shared
                                    .describe(name)
                                    .resolve()
                                    .declaredMethods.size
                            }
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(3, locator.located.toSet().size)
        assertTrue(shared.cachedTypes() <= 1024)
    }

    @Test
    fun `primitives and arrays of them resolve without a read`() {
        val locator = counting()
        val shared = JdkTypePool(locator)

        for (name in listOf("void", "int", "boolean", "[I", "[[J", "[Ljava.lang.String;")) {
            assertTrue(shared.describe(name).isResolved, name)
        }
        assertEquals(listOf("java.lang.String"), locator.located.toList())

        val string = shared.describe("java.lang.String").resolve()
        assertTrue(
            string.declaredMethods.all {
                it.returnType
                    .asErasure()
                    .name
                    .isNotEmpty() &&
                    it.parameters.all { p ->
                        p.type
                            .asErasure()
                            .name
                            .isNotEmpty()
                    }
            },
        )
    }

    @Test
    fun `a java type that names a type outside java still has it, and that type is not answered by name`() {
        val shared = JdkTypePool()

        val button = shared.describe("java.awt.Button").resolve()

        assertTrue(button.interfaces.asErasures().any { it.name == "javax.accessibility.Accessible" })
        assertFalse(shared.describe("javax.accessibility.Accessible").isResolved)
    }
}
