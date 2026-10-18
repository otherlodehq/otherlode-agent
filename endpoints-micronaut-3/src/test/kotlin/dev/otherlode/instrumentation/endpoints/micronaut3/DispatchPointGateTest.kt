package dev.otherlode.instrumentation.endpoints.micronaut3

import dev.otherlode.export.DisabledEndpointModuleKind
import io.micronaut.context.ApplicationContext
import io.micronaut.web.router.Router
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [Micronaut3Module] declares a router's routes only once a dispatch point it counts at has
 * been constructed, in either order, and reports the module off at the first manifest when none was.
 * A context that starts no server builds a router and no dispatch point.
 */
class DispatchPointGateTest {
    private val quiet = mapOf<String, Any>("endpoints.all.enabled" to false)

    @AfterTest
    fun forget() = forgetDisabled("micronaut-3")

    private fun <T> routerOnly(
        prefix: String,
        block: () -> T,
    ): T =
        ApplicationContext.run(quiet + ("micronaut.server.context-path" to prefix)).use { context ->
            context.getBean(Router::class.java)
            block()
        }

    @Test
    fun `a router built with no dispatch point declares nothing and a manifest after the grace period reports the module`() {
        withModule(expectNoneDisabled = false) { registry ->
            routerOnly("/pending") {
                assertEquals(emptySet(), identities(registry))

                beforeManifest()

                val disabled = registry.disabledModules().single()
                assertEquals("micronaut-3", disabled.module)
                assertEquals(DisabledEndpointModuleKind.ROUTES_NOT_COUNTED, disabled.kind)
                assertTrue("RoutingInBoundHandler" in disabled.reason, disabled.reason)
                assertEquals(emptySet(), identities(registry))
            }
        }
    }

    @Test
    fun `a router held at a manifest within the grace period and armed before the next declares and reports nothing`() {
        withModule { registry ->
            routerOnly("/pending") {
                beforeManifest(graceMillis = 60_000)
                assertEquals(emptyList(), registry.disabledModules())

                serve {
                    assertTrue("GET /pending/java/hello" in identities(registry), identities(registry).toString())
                }
            }
        }
    }

    @Test
    fun `a router held at the final flush switches the module off within the grace period`() {
        withModule(expectNoneDisabled = false) { registry ->
            routerOnly("/pending") {
                beforeManifest(graceMillis = 60_000, finalFlush = true)

                assertEquals(DisabledEndpointModuleKind.ROUTES_NOT_COUNTED, registry.disabledModules().single().kind)
            }
        }
    }

    @Test
    fun `a router built and then a dispatch point constructed declares the router`() {
        withModule { registry ->
            routerOnly("/pending") {
                assertEquals(emptySet(), identities(registry))

                serve {
                    assertTrue("GET /pending/java/hello" in identities(registry), identities(registry).toString())
                    assertTrue("GET /java/hello" in identities(registry))
                }
            }
            beforeManifest()
        }
    }

    @Test
    fun `a dispatch point constructed and then a router built declares the router`() {
        withModule { registry ->
            serve {
                assertTrue("GET /pending/java/hello" !in identities(registry))

                routerOnly("/later") {
                    assertTrue("GET /later/java/hello" in identities(registry), identities(registry).toString())
                }
            }
            beforeManifest()
        }
    }

    @Test
    fun `a dispatch point constructed after the module was reported finds it off and declares nothing`() {
        withModule(expectNoneDisabled = false) { registry ->
            routerOnly("/pending") {
                beforeManifest()

                serve { port ->
                    assertEquals(200, send(port, "GET", "/java/hello"))
                }

                assertEquals(emptySet(), identities(registry))
                assertEquals(emptyMap(), hits(registry))
                assertEquals(DisabledEndpointModuleKind.ROUTES_NOT_COUNTED, registry.disabledModules().single().kind)
            }
        }
    }

    @Test
    fun `a manifest with no router built reports nothing`() {
        withModule { registry ->
            beforeManifest()

            assertEquals(emptyList(), registry.disabledModules())
        }
    }

    @Test
    fun `a running server is armed, so a manifest reports nothing and its routes are declared`() {
        withModule { registry ->
            serve {
                beforeManifest()

                assertEquals(emptyList(), registry.disabledModules())
                assertTrue("GET /java/hello" in identities(registry))
            }
        }
    }
}
