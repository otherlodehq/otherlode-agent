package com.example.agenttarget

import dev.otherlode.testkit.OtherlodeTestCollector
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This suite runs against the testkit's shadow jar, as an adopter's test classpath does. The wire
 * module and protobuf-java are inside it only under the testkit's own prefix.
 */
class TestkitJarTest {
    @Test
    fun `the testkit loads from its shadow jar, with the wire module and protobuf relocated`() {
        val location = OtherlodeTestCollector::class.java.protectionDomain.codeSource.location.path
        assertTrue(location.endsWith(".jar") && "otherlode-testkit" in location && "-plain" !in location, location)

        val loader = OtherlodeTestCollector::class.java.classLoader
        assertNull(loader.getResource("dev/otherlode/export/ProbeManifest.class"), "unrelocated wire module")
        assertNull(loader.getResource("com/google/protobuf/Message.class"), "unrelocated protobuf-java")
        assertNotNull(loader.getResource("dev/otherlode/testkit/shaded/export/ProbeManifest.class"))
    }

    @Test
    fun `the testkit's version is the one in the jar manifest`() {
        val version = OtherlodeTestCollector::class.java.`package`.implementationVersion
        assertFalse(version.isNullOrEmpty(), "the shadow jar's manifest carries no Implementation-Version")
    }
}
