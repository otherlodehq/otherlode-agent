package dev.otherlode.instrumentation

import net.bytebuddy.dynamic.ClassFileLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlaceholderPoolLazinessTest {
    @Test
    fun `no pool is built until a type is described`() {
        val pool = LazyPlaceholderPool(ClassFileLocator.NoOp.INSTANCE)

        assertNull(pool.created())
        pool.clear()
        assertNull(pool.created())

        pool.describe("com.example.absent.Missing").resolve()
        val created = assertNotNull(pool.created())
        pool.describe("com.example.absent.Other").resolve()
        assertSame(created, pool.created())
    }

    @Test
    fun `the strategy answers for the transform before and after a pool exists`() {
        val strategy = PlaceholderPoolStrategy()
        val pool = strategy.typePool(ClassFileLocator.NoOp.INSTANCE, null)

        assertEquals(emptySet(), strategy.substituted())
        assertEquals(emptySet(), strategy.substantive())

        pool.describe("com.example.absent.Missing").resolve().modifiers

        assertEquals(setOf("com.example.absent.Missing"), strategy.substituted())
        strategy.release()
        assertEquals(emptySet(), strategy.substituted())
    }

    @Test
    fun `class bytes noted before the pool exists reach it`() {
        val strategy = PlaceholderPoolStrategy()
        val pool = strategy.typePool(ClassFileLocator.NoOp.INSTANCE, null)
        val ownBytes = javaClass.getResourceAsStream("PlaceholderPoolLazinessTest.class")!!.use { it.readBytes() }
        strategy.noteAnnotationTypes(ownBytes)
        pool.describe("com.example.absent.Missing").resolve().modifiers

        assertEquals(setOf("com.example.absent.Missing"), strategy.substantive())
    }
}
