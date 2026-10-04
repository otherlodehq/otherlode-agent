package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MatrixTest {
    @Test
    fun `round zero keeps the declared order`() {
        assertEquals(listOf(Variant.NONE, Variant.AGENT, Variant.AGENT_BASELINE), rotatedVariants(0))
    }

    @Test
    fun `each round starts one variant later`() {
        assertEquals(listOf(Variant.AGENT, Variant.AGENT_BASELINE, Variant.NONE), rotatedVariants(1))
        assertEquals(listOf(Variant.AGENT_BASELINE, Variant.NONE, Variant.AGENT), rotatedVariants(2))
        assertEquals(rotatedVariants(0), rotatedVariants(3))
    }

    @Test
    fun `over three rounds every variant goes first once`() {
        assertEquals(Variant.entries.toSet(), (0 until 3).map { rotatedVariants(it).first() }.toSet())
    }

    @Test
    fun `the headline config covers petclinic and the ceiling covers spring`() {
        assertEquals("org.springframework.samples.petclinic", Config.HEADLINE.includePackages)
        assertEquals("org.springframework", Config.CEILING.includePackages)
        assertEquals(Config.CEILING, Config.parse("ceiling"))
    }

    @Test
    fun `agent options per variant`() {
        assertNull(agentOptions(Config.HEADLINE, Variant.NONE))
        assertEquals(
            "exportUrl=http://collector:4319,serviceName=petclinic,includePackages=org.springframework.samples.petclinic",
            agentOptions(Config.HEADLINE, Variant.AGENT),
        )
        assertEquals(
            "exportUrl=http://collector:4319,serviceName=petclinic,includePackages=org.springframework,staticBaselineEnabled=true",
            agentOptions(Config.CEILING, Variant.AGENT_BASELINE),
        )
    }
}
