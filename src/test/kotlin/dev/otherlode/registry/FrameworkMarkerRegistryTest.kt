package dev.otherlode.registry

import dev.otherlode.export.FrameworkMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameworkMarkerRegistryTest {
    private val arrayList = java.util.ArrayList::class.java.name
    private val hashMap = java.util.HashMap::class.java.name
    private val notLoaded = "com.example.markers.NotLoaded"

    private fun loaded(vararg classes: Class<*>): Array<Class<*>> = arrayOf(*classes)

    @Test
    fun `a marker is seen when every class of one set is defined, and carries that set`() {
        val registry =
            FrameworkMarkerRegistry(listOf(FrameworkMarkerDefinition("both", listOf(listOf(arrayList, hashMap)))))

        registry.observe(loaded(java.util.ArrayList::class.java, java.util.HashMap::class.java), seenAt = 7L)

        assertEquals(listOf(FrameworkMarker("both", listOf(arrayList, hashMap), 7L)), registry.seenMarkers())
    }

    @Test
    fun `a marker whose set is only partly defined is not seen`() {
        val registry =
            FrameworkMarkerRegistry(listOf(FrameworkMarkerDefinition("both", listOf(listOf(arrayList, notLoaded)))))

        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 7L)

        assertEquals(emptyList(), registry.seenMarkers())
    }

    @Test
    fun `any one of several alternative sets is enough, and the matching set is reported`() {
        val registry =
            FrameworkMarkerRegistry(
                listOf(FrameworkMarkerDefinition("either", listOf(listOf(notLoaded), listOf(hashMap)))),
            )

        registry.observe(loaded(java.util.HashMap::class.java), seenAt = 9L)

        assertEquals(listOf(FrameworkMarker("either", listOf(hashMap), 9L)), registry.seenMarkers())
    }

    @Test
    fun `seeing a marker never loads a class`() {
        val name = "dev.otherlode.registry.FrameworkMarkerRegistryTest\$Untouched"
        val registry = FrameworkMarkerRegistry(listOf(FrameworkMarkerDefinition("fixture", listOf(listOf(name)))))
        initialised = false

        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 1L)

        assertFalse(initialised)
        assertEquals(emptyList(), registry.seenMarkers())
    }

    @Test
    fun `a marker stays seen after the class is gone from a later walk, with its first time`() {
        val registry = FrameworkMarkerRegistry(listOf(FrameworkMarkerDefinition("m", listOf(listOf(arrayList)))))

        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 1L)
        registry.observe(loaded(), seenAt = 2L)
        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 3L)

        assertEquals(listOf(FrameworkMarker("m", listOf(arrayList), 1L)), registry.seenMarkers())
    }

    @Test
    fun `a marker is sent until a confirmed manifest carried it, then not again`() {
        val registry = FrameworkMarkerRegistry(listOf(FrameworkMarkerDefinition("m", listOf(listOf(arrayList)))))
        assertNull(registry.computeManifestEntries(), "nothing seen yet")
        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 1L)

        val first = assertNotNull(registry.computeManifestEntries())
        val unconfirmed = assertNotNull(registry.computeManifestEntries(), "still due until confirmed")
        assertEquals(first.markers, unconfirmed.markers)

        registry.advanceManifest(first)

        assertNull(registry.computeManifestEntries())
        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 5L)
        assertNull(registry.computeManifestEntries(), "seeing it again does not resend it")
    }

    @Test
    fun `a marker seen after an earlier one was delivered is sent alone`() {
        val registry =
            FrameworkMarkerRegistry(
                listOf(
                    FrameworkMarkerDefinition("a", listOf(listOf(arrayList))),
                    FrameworkMarkerDefinition("b", listOf(listOf(hashMap))),
                ),
            )
        registry.observe(loaded(java.util.ArrayList::class.java), seenAt = 1L)
        registry.advanceManifest(assertNotNull(registry.computeManifestEntries()))

        registry.observe(loaded(java.util.ArrayList::class.java, java.util.HashMap::class.java), seenAt = 2L)

        assertEquals(listOf("b"), assertNotNull(registry.computeManifestEntries()).markers.map { it.id })
    }

    @Test
    fun `the fixed list has unique kebab-case ids and well-formed class sets`() {
        val ids = FrameworkMarkers.ALL.map { it.id }

        assertEquals(ids.distinct(), ids)
        assertTrue(ids.all { Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(it) }, "kebab-case: $ids")
        for (marker in FrameworkMarkers.ALL) {
            assertTrue(marker.classSets.isNotEmpty() && marker.classSets.all { it.isNotEmpty() }, "${marker.id} has a class set")
            assertTrue(
                marker.classSets.flatten().all { Regex("[A-Za-z0-9_.$]+").matches(it) && '/' !in it },
                "${marker.id} names binary class names",
            )
        }
    }

    class Untouched {
        companion object {
            init {
                initialised = true
            }
        }
    }

    private companion object {
        @Volatile
        var initialised = false
    }
}
