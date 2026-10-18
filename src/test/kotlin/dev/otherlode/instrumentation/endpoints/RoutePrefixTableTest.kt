package dev.otherlode.instrumentation.endpoints

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.BootstrapHolder
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutePrefixTableTest {
    @BeforeEach
    fun installBootstrapHolder() {
        BootstrapHolder.install(ByteBuddyAgent.install())
    }

    @Test
    fun `a route nobody prefixed resolves to itself`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)

        assertEquals("/orders", table.resolve("/orders"))
    }

    @Test
    fun `a recorded route resolves to the route it was built from`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/shop/orders", "/orders")

        assertEquals("/orders", table.resolve("/shop/orders"))
        assertEquals("/orders", table.resolve("/orders"))
    }

    @Test
    fun `a route prefixed twice resolves through both steps`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/api/users", "/users")
        table.record("/shop/api/users", "/api/users")

        assertEquals("/users", table.resolve("/shop/api/users"))
        assertEquals("/users", table.resolve("/api/users"))
    }

    @Test
    fun `a prefixer that changed nothing records nothing`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record(null, "/orders")
        table.record("/shop", null)
        table.record("/shop", "")

        assertEquals("/shop", table.resolve("/shop"))
    }

    @Test
    fun `a route a root context left unchanged resolves to itself`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/orders", "/orders")

        assertEquals("/orders", table.resolve("/orders"))
    }

    @Test
    fun `an unchanged route recorded after a prefixed one makes the route ambiguous`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/a/orders", "/orders")
        table.record("/a/orders", "/a/orders")

        assertEquals("/a/orders", table.resolve("/a/orders"))
    }

    @Test
    fun `an unchanged route recorded before a prefixed one makes the route ambiguous`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/a/orders", "/a/orders")
        table.record("/a/orders", "/orders")

        assertEquals("/a/orders", table.resolve("/a/orders"))
    }

    @Test
    fun `a full table stops adding and keeps every translation it holds`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(2)
        table.record("/a/one", "/one")
        table.record("/a/two", "/two")
        table.record("/a/three", "/three")

        assertEquals("/one", table.resolve("/a/one"))
        assertEquals("/two", table.resolve("/a/two"))
        assertEquals("/a/three", table.resolve("/a/three"))
    }

    @Test
    fun `a route two prefixers built differently is left as it was`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/a/b/c", "/b/c")
        table.record("/a/b/c", "/c")

        assertEquals("/a/b/c", table.resolve("/a/b/c"))
    }

    @Test
    fun `an ambiguous step anywhere in a chain leaves the route as it was`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/shop/api/users", "/api/users")
        table.record("/api/users", "/users")
        table.record("/api/users", "/other")

        assertEquals("/shop/api/users", table.resolve("/shop/api/users"))
    }

    @Test
    fun `a cycle leaves the route as it was`() {
        val table = OtherlodeEndpoints.RoutePrefixTable(8)
        table.record("/a", "/b")
        table.record("/b", "/a")

        assertEquals("/a", table.resolve("/a"))
    }

    @Test
    fun `the seam records and resolves through one table`() {
        OtherlodeEndpoints.recordRoutePrefix("/seam-test/orders", "/orders")

        assertEquals("/orders", OtherlodeEndpoints.resolveRoutePrefix("/seam-test/orders"))
        assertEquals("/unknown", OtherlodeEndpoints.resolveRoutePrefix("/unknown"))
    }
}
