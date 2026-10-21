package dev.otherlode.instrumentation.endpoints.javalin6

import dev.otherlode.export.DisabledEndpointModuleKind
import io.javalin.Javalin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Javalin 6.0.0 and 6.0.1 match [Javalin6Module]'s shape but keep `Endpoint.getHandler()` internal,
 * so the module switches itself off at the first registration and says so.
 */
class Javalin60Test {
    @Test
    fun `Javalin 6_0 disables the module with a LINKAGE_ERROR and keeps serving`() {
        withModules(expectNoneDisabled = false) { registry ->
            val app = Javalin.create().get("/hello") { ctx -> ctx.result("hello") }.start("127.0.0.1", 0)
            try {
                assertEquals(200, send(app.port(), "GET", "/hello"))
            } finally {
                app.stop()
            }

            val disabled = registry.disabledModules().single()
            assertEquals("javalin-6", disabled.module)
            assertEquals(DisabledEndpointModuleKind.LINKAGE_ERROR, disabled.kind)
            assertTrue("getHandler" in disabled.reason, disabled.reason)
            assertTrue(registry.endpoints().isEmpty())
        }
    }
}
