package dev.otherlode.instrumentation.endpoints.otelbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentOrderTest {
    @Test
    fun `a Spring MVC request is counted and no module is disabled`() {
        springApp("").use { app -> assertEquals(200, app.get("/orders")) }

        val observed = observedEndpoints()
        val disabled = PrefixTestAgent.registry.disabledModules()

        assertEquals(1L, observed.single { it.framework == "spring-webmvc" && it.template == "/orders" }.hits, "$observed")
        assertTrue(disabled.isEmpty(), "modules were disabled: $disabled")
        assertTrue(PrefixTestAgent.warnings.isEmpty(), "the agent warned: ${PrefixTestAgent.warnings}")
    }
}
