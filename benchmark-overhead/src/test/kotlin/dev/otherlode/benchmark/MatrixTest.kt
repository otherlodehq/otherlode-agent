package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `over three rounds every default variant goes first once`() {
        assertEquals(DEFAULT_VARIANTS.toSet(), (0 until 3).map { rotatedVariants(it).first() }.toSet())
    }

    @Test
    fun `the default variants leave the nightly job unchanged`() {
        assertEquals(listOf(Variant.NONE, Variant.AGENT, Variant.AGENT_BASELINE), DEFAULT_VARIANTS)
    }

    @Test
    fun `rotation stays inside the chosen list`() {
        val chosen = listOf(Variant.NONE, Variant.OTEL, Variant.AGENT)
        assertEquals(chosen, rotatedVariants(0, chosen))
        assertEquals(listOf(Variant.OTEL, Variant.AGENT, Variant.NONE), rotatedVariants(1, chosen))
        assertEquals(listOf(Variant.AGENT, Variant.NONE, Variant.OTEL), rotatedVariants(2, chosen))
        assertEquals(chosen, rotatedVariants(3, chosen))
        assertEquals(chosen.toSet(), (0 until 3).map { rotatedVariants(it, chosen).first() }.toSet())
    }

    @Test
    fun `a list of variants is read in its own order`() {
        assertEquals(listOf(Variant.NONE, Variant.OTEL, Variant.AGENT), parseVariants("none,otel,agent"))
        assertEquals(listOf(Variant.AGENT_BASELINE, Variant.NONE), parseVariants(" agent-baseline , none "))
        assertEquals(listOf(Variant.NONE), parseVariants("none"))
    }

    @Test
    fun `an unknown variant is refused and named`() {
        val e = assertThrows(IllegalStateException::class.java) { parseVariants("none,jaeger") }
        assertTrue(e.message!!.contains("jaeger"), e.message)
        assertThrows(IllegalStateException::class.java) { parseVariants("none,") }
    }

    @Test
    fun `a repeated variant is refused`() {
        val e = assertThrows(IllegalStateException::class.java) { parseVariants("none,agent,agent") }
        assertTrue(e.message!!.contains("agent"), e.message)
    }

    @Test
    fun `none is required`() {
        val e = assertThrows(IllegalStateException::class.java) { parseVariants("agent,otel") }
        assertTrue(e.message!!.contains("none"), e.message)
    }

    @Test
    fun `only the agent variants are Otherlode's`() {
        assertEquals(
            listOf(false, true, true, false),
            listOf(Variant.NONE, Variant.AGENT, Variant.AGENT_BASELINE, Variant.OTEL).map { it.otherlode },
        )
        assertEquals("otel", Variant.OTEL.id)
    }

    @Test
    fun `the otel variant has no Otherlode agent options`() {
        assertNull(agentOptions(Config.HEADLINE, Variant.OTEL))
    }

    @Test
    fun `the otel JVM options add the agent, the sink and the service name and leave the rest at the defaults`() {
        assertEquals(
            listOf(
                "-javaagent:/app/opentelemetry-javaagent.jar",
                "-Dotel.exporter.otlp.endpoint=http://otlp-sink:4318",
                "-Dotel.service.name=petclinic",
            ),
            otelJvmOptions(Variant.OTEL),
        )
    }

    @Test
    fun `the other variants have no otel JVM options`() {
        assertEquals(emptyList<String>(), otelJvmOptions(Variant.NONE))
        assertEquals(emptyList<String>(), otelJvmOptions(Variant.AGENT))
        assertEquals(emptyList<String>(), otelJvmOptions(Variant.AGENT_BASELINE))
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
