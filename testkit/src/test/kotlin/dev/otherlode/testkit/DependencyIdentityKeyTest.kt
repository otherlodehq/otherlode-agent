package dev.otherlode.testkit

import dev.otherlode.export.DependencyDiscoverySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DependencyIdentityKeyTest {
    private fun key(vararg ids: Pair<String, String>) =
        DependencyView(
            dependencyId = 1,
            identities = ids.map { (group, artifact) -> DependencyIdentityView(group, artifact, "1") },
            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 1,
            location = "x.jar",
        ).identityKey

    @Test
    fun `identity keys escape their separators as the server does, so different identities never share one`() {
        assertEquals("com.acme:fat,com.google.guava:guava", key("com.google.guava" to "guava", "com.acme" to "fat"))
        assertNotEquals(key("a:b" to "c"), key("a" to "b:c"))
        assertNotEquals(key("org.x" to "a", "org.y" to "b"), key("org.x" to "a,org.y:b"))
        assertEquals("a\\\\b:c\\:d\\,e", key("a\\b" to "c:d,e"))
    }
}
