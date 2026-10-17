package dev.otherlode.instrumentation.endpoints.springwebmvc

import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A route written in Spring's Kotlin DSL has its handler inside a class of Spring's own, so no
 * method of the application's is the handler and the endpoint reports none.
 */
class SpringWebMvcDslHandlerTest {
    private fun <T> withModule(block: (EndpointRegistry) -> T): T {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)
        try {
            val result = block(registry)
            assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
            return result
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    @Test
    fun `a router DSL route is declared and counted with no handler`() {
        withModule { registry ->
            val routes = router { GET("/dsl") { ServerResponse.ok().build() } }
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            mockMvc.perform(get("/dsl"))

            val endpoint = registry.endpoints().single()
            assertEquals("GET /dsl", "${endpoint.verb} ${endpoint.routeTemplate}")
            assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertNull(endpoint.handlerDescriptor)
            val deltas = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }
            assertEquals(1L, deltas.single().hitsTotal)
        }
    }

    @Test
    fun `a router DSL route found at dispatch has no handler`() {
        withModule { registry ->
            val routes = router { GET("/found", RequestPredicate { true }) { ServerResponse.ok().build() } }
            val mockMvc = MockMvcBuilders.webAppContextSetup(buildFunctionalWebApplicationContext(routes)).build()

            mockMvc.perform(get("/found"))

            val endpoint = registry.endpoints().single()
            assertEquals(EndpointDiscoverySource.DISPATCH, endpoint.discoverySource)
            assertNull(endpoint.handlerClass)
            assertNull(endpoint.handlerMethod)
            assertNull(endpoint.handlerDescriptor)
            val deltas = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }
            assertEquals(1L, deltas.single().hitsTotal)
        }
    }
}
