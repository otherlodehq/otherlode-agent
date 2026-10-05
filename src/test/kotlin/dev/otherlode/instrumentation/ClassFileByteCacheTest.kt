package dev.otherlode.instrumentation

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassFileByteCacheTest {
    /** A loader serving [files] as class files, counting every request by class-file path. */
    private class ServingLoader(
        val files: Map<String, ByteArray>,
    ) : ClassLoader(null) {
        val reads = ConcurrentHashMap<String, AtomicInteger>()

        override fun getResourceAsStream(name: String): InputStream? {
            reads.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            val bytes = files[name.removeSuffix(".class").replace('/', '.')] ?: return null
            return ByteArrayInputStream(bytes)
        }

        fun readsOf(className: String): Int = reads[className.replace('.', '/') + ".class"]?.get() ?: 0
    }

    private fun bytes(
        size: Int,
        fill: Int = size,
    ) = ByteArray(size) { fill.toByte() }

    private fun ClassFileByteCache.read(
        loader: ClassLoader?,
        name: String,
    ): ByteArray? {
        val resolution = locatorFor(loader).locate(name)
        return if (resolution.isResolved) resolution.resolve() else null
    }

    @Test
    fun `a second read of a class file through one loader makes no read through the loader`() {
        val loader = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()

        assertContentEquals(bytes(10), cache.read(loader, "a.A"))
        assertContentEquals(bytes(10), cache.read(loader, "a.A"))

        assertEquals(1, loader.readsOf("a.A"))
    }

    @Test
    fun `a locator made later still reads from the cache`() {
        val loader = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()

        cache.read(loader, "a.A")
        cache.read(loader, "a.A")

        assertEquals(1, loader.readsOf("a.A"))
    }

    @Test
    fun `the same name through two loaders is read through each`() {
        val first = ServingLoader(mapOf("a.A" to bytes(10, 1)))
        val second = ServingLoader(mapOf("a.A" to bytes(10, 2)))
        val cache = ClassFileByteCache()

        assertContentEquals(bytes(10, 1), cache.read(first, "a.A"))
        assertContentEquals(bytes(10, 2), cache.read(second, "a.A"))
        assertContentEquals(bytes(10, 1), cache.read(first, "a.A"))

        assertEquals(1, first.readsOf("a.A"))
        assertEquals(1, second.readsOf("a.A"))
    }

    @Test
    fun `a class file that does not exist is read once`() {
        val loader = ServingLoader(emptyMap())
        val cache = ClassFileByteCache()

        assertNull(cache.read(loader, "a.Missing"))
        assertNull(cache.read(loader, "a.Missing"))

        assertEquals(1, loader.readsOf("a.Missing"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `the budget evicts the least recently used entry and never holds more than the cap`() {
        val files = ('A'..'E').associate { "a.$it" to bytes(40, it.code) }
        val loader = ServingLoader(files)
        val cache = ClassFileByteCache(capBytes = 100)

        cache.read(loader, "a.A")
        cache.read(loader, "a.B")
        cache.read(loader, "a.A")
        cache.read(loader, "a.C")

        assertTrue(cache.cachedBytes <= 100, "${cache.cachedBytes}")
        assertEquals(2, cache.size)
        cache.read(loader, "a.A")
        assertEquals(1, loader.readsOf("a.A"), "A was used after B, so B went")
        cache.read(loader, "a.B")
        assertEquals(2, loader.readsOf("a.B"))
        ('A'..'E').forEach {
            cache.read(loader, "a.$it")
            assertTrue(cache.cachedBytes <= 100, "${cache.cachedBytes}")
        }
    }

    @Test
    fun `a class file larger than the cap is returned and not cached`() {
        val loader = ServingLoader(mapOf("a.Big" to bytes(200)))
        val cache = ClassFileByteCache(capBytes = 100)

        assertContentEquals(bytes(200), cache.read(loader, "a.Big"))
        cache.read(loader, "a.Big")

        assertEquals(2, loader.readsOf("a.Big"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `a flood of misses is bounded by the cap`() {
        val loader = ServingLoader(emptyMap())
        val cache = ClassFileByteCache(capBytes = 10 * ClassFileByteCache.NEGATIVE_WEIGHT)

        repeat(1000) { cache.read(loader, "a.Missing$it") }

        assertEquals(10, cache.size)
        assertTrue(cache.cachedBytes <= 10 * ClassFileByteCache.NEGATIVE_WEIGHT)
    }

    @Test
    fun `dropping one loader leaves another loader's entries`() {
        val first = ServingLoader(mapOf("a.A" to bytes(10)))
        val second = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()
        cache.read(first, "a.A")
        cache.read(first, "a.Missing")
        cache.read(second, "a.A")

        cache.dropLoader(first)

        assertEquals(1, cache.size)
        assertEquals(10, cache.cachedBytes)
        cache.read(second, "a.A")
        assertEquals(1, second.readsOf("a.A"))
        cache.read(first, "a.A")
        assertEquals(2, first.readsOf("a.A"))
    }

    @Test
    fun `dropping everything empties the cache`() {
        val loader = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()
        cache.read(loader, "a.A")
        cache.read(loader, "a.Missing")
        cache.read(null, "java.lang.Object")

        cache.dropAll()

        assertEquals(0, cache.size)
        assertEquals(0, cache.cachedBytes)
    }

    @Test
    fun `the bootstrap loader is a loader of its own`() {
        val cache = ClassFileByteCache()
        val loader = ServingLoader(mapOf("java.lang.Object" to bytes(10)))

        val object1 = cache.read(null, "java.lang.Object")
        val object2 = cache.read(null, "java.lang.Object")
        cache.read(loader, "java.lang.Object")

        assertTrue(object1 != null && object1.isNotEmpty())
        assertTrue(object1 === object2, "the second read is the cached array")
        assertEquals(2, cache.size)
        assertEquals(1, loader.readsOf("java.lang.Object"))
        cache.dropLoader(null)
        assertEquals(1, cache.size)
        assertFalse(cache.read(loader, "java.lang.Object")!!.size > 10)
    }

    @Test
    fun `entries of a loader that becomes unreachable are collected`() {
        val cache = ClassFileByteCache()
        val reference = fill(cache)

        var attempts = 0
        while (cache.size > 0 && attempts++ < 100) {
            System.gc()
            Thread.sleep(20)
        }

        assertNull(reference.get(), "the loader was collected")
        assertEquals(0, cache.size)
        assertEquals(0, cache.cachedBytes)
    }

    private fun fill(cache: ClassFileByteCache): WeakReference<ClassLoader> {
        val loader = ServingLoader(mapOf("a.A" to bytes(10)))
        cache.read(loader, "a.A")
        cache.read(loader, "a.Missing")
        return WeakReference(loader)
    }

    @Test
    fun `a loader that read or stored is reported active once, and the report clears the marks`() {
        val loaderA = ServingLoader(mapOf("a.A" to bytes(10)))
        val loaderB = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()

        cache.read(loaderA, "a.A")
        cache.read(null, "java.lang.Object")
        cache.read(loaderA, "a.A")

        val active = cache.takeActiveLoaders()
        assertTrue(loaderA in active)
        assertTrue(null in active)
        assertFalse(loaderB in active)
        assertTrue(cache.takeActiveLoaders().isEmpty())
    }

    @Test
    fun `dropping the loaders not in a set keeps active and listed loaders and drops the rest`() {
        val quiet = ServingLoader(mapOf("a.A" to bytes(10)))
        val listed = ServingLoader(mapOf("a.A" to bytes(10)))
        val active = ServingLoader(mapOf("a.A" to bytes(10)))
        val cache = ClassFileByteCache()
        cache.read(quiet, "a.A")
        cache.read(listed, "a.A")
        cache.read(null, "java.lang.Object")
        cache.takeActiveLoaders()
        cache.read(active, "a.A")

        cache.dropLoadersNotIn(setOf(listed))

        assertEquals(2, cache.size)
        cache.read(quiet, "a.A")
        cache.read(listed, "a.A")
        cache.read(active, "a.A")
        assertEquals(2, quiet.readsOf("a.A"))
        assertEquals(1, listed.readsOf("a.A"))
        assertEquals(1, active.readsOf("a.A"))
    }
}
