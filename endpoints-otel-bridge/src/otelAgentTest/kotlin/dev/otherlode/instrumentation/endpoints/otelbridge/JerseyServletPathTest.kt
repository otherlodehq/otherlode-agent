package dev.otherlode.instrumentation.endpoints.otelbridge

import com.example.otelbridge.UsersResource
import dev.otherlode.bootstrap.OtherlodeEndpoints
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jersey served by a servlet mapped under `/api` with a wildcard, in the context path `/shop`,
 * under the OpenTelemetry Java agent. OpenTelemetry reports the route as `/shop/api/users`, with the context
 * path and the servlet mapping both in front of the resource's `/users`.
 */
class JerseyServletPathTest {
    @Test
    fun `a JAX-RS request under a context path and a servlet mapping is counted once and the bridge adds no endpoint`() {
        TomcatApp("/shop") { context ->
            context.servlet("jersey", ServletContainer(ResourceConfig(UsersResource::class.java)), "/api/*")
        }.use { app ->
            assertEquals(200, app.get("/shop/api/users"))
        }

        val observed = observedEndpoints()

        assertEquals(listOf(1L), observed.filter { it.framework == "jaxrs" && it.template == "/users" }.map { it.hits }, "$observed")
        assertTrue(observed.none { it.framework == "otel" }, "the bridge recorded an endpoint: $observed")
        assertEquals("/users", OtherlodeEndpoints.resolveRoutePrefix("/shop/api/users"))
    }
}
