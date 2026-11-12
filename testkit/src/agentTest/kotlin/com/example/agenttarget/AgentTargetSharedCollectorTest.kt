package com.example.agenttarget

import dev.otherlode.testkit.OtherlodeTestCollector
import dev.otherlode.testkit.junit5.OtherlodeExtension
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@ExtendWith(OtherlodeExtension::class)
@Order(2)
class AgentTargetSharedCollectorTest {
    @Test
    fun `the shared collector is still queryable, and is the same instance OtherlodeExtension hands out`(collector: OtherlodeTestCollector) {
        assertSame(OtherlodeExtension.collector(), collector)
        assertTrue(collector.wasHit("com.example.agenttarget.AgentTarget", "exercised"))
    }
}
