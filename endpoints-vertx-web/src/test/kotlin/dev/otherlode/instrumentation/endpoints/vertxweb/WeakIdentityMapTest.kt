package dev.otherlode.instrumentation.endpoints.vertxweb

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class WeakIdentityMapTest {
    private class Equal(
        val value: Int,
    ) {
        override fun equals(other: Any?) = other is Equal && other.value == value

        override fun hashCode() = value
    }

    @Test
    fun `objects are compared by identity, whatever equals says`() {
        val map = WeakIdentityMap<String>()
        val first = Equal(1)
        val second = Equal(1)
        map.getOrPut(first) { "first" }
        map.getOrPut(second) { "second" }

        assertEquals("first", map[first])
        assertEquals("second", map[second])
        assertNull(map[Equal(1)])
        assertSame(map.getOrPut(first) { "other" }, map[first])
    }

    @Test
    fun `a collected key leaves the map at a later write`() {
        val map = WeakIdentityMap<String>()
        var key: Any? = Equal(7)
        val ref = WeakReference(key)
        map.getOrPut(key!!) { "gone" }
        key = null
        val kept = ArrayList<Any>()
        var attempts = 0
        while (map.size() != kept.size && attempts++ < 100) {
            System.gc()
            Thread.sleep(20)
            kept += Equal(100 + attempts)
            map.getOrPut(kept.last()) { "kept" }
        }

        assertNull(ref.get())
        assertEquals(kept.size, map.size())
    }

    @Test
    fun `a value is made once per key`() {
        val map = WeakIdentityMap<Any>()
        val key = Any()
        val value = map.getOrPut(key) { Any() }

        assertSame(value, map.getOrPut(key) { Any() })
        assertNotSame(value, map.getOrPut(Any()) { Any() })
    }
}
