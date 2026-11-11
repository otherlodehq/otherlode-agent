package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContainerAddressTest : ServletTestSupport() {
    @Test
    fun `a started container holds its port at the loopback address`() {
        container
            .start("") {
                servlet("app", GetPostServlet(), "/address/app")
            }.use { app ->
                assertFailsWith<BindException> {
                    ServerSocket().use {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), app.port))
                    }
                }
                assertEquals(200, app.send("/address/app"))
            }
    }
}
