package com.example.agenttarget

import dev.otherlode.testkit.DependencyUsage
import dev.otherlode.testkit.OtherlodeTestCollector
import dev.otherlode.testkit.junit5.OtherlodeExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(OtherlodeExtension::class)
@Order(3)
class DependencyUsageAgentTest {
    @Test
    fun `a startup dependency nothing loads is unloaded, and one a hit method calls is used`(collector: OtherlodeTestCollector) {
        assertEquals("hello otherlode", DependencyUser().greet())

        collector.awaitDependency("com.example.fixture", "dep-used", Duration.ofSeconds(30))
        collector.awaitDependency(null, "dep-unused", Duration.ofSeconds(30))
        collector.awaitDependenciesListed(Duration.ofSeconds(30))
        collector.awaitSettled(Duration.ofSeconds(15))

        val unused = collector.dependency(null, "dep-unused")
        assertEquals(DependencyUsage.UNLOADED, unused.status)
        assertEquals(0L, unused.loadedClassesTotal)
        assertTrue(unused in collector.unloadedDependencies())

        val used = collector.dependency("com.example.fixture", "dep-used")
        assertEquals(DependencyUsage.USED, used.status, used.toString())
        assertEquals(1L, used.loadedClassesTotal)
        assertEquals(1, used.classCount)

        // The split queries throw until the scan's chunks arrive; this suite sets staticBaselineEnabled=true.
        val split = awaitSplit(Duration.ofSeconds(30)) { collector.unreferencedDependencies() + collector.unreachedDependencies() }
        val splitKeys = split.map { it.identityKey }
        assertTrue(":dep-unused" !in splitKeys && "com.example.fixture:dep-used" !in splitKeys, splitKeys.toString())
    }

    private fun <T> awaitSplit(
        timeout: Duration,
        query: () -> T,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            try {
                return query()
            } catch (notYet: IllegalStateException) {
                if (System.nanoTime() > deadline) throw notYet
                Thread.sleep(200)
            }
        }
    }
}
