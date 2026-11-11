package dev.otherlode.instrumentation.endpoints.servlet

import com.example.servlet.fixture.GetPostServlet
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.ServletInfo
import io.undertow.servlet.util.ImmediateInstanceFactory
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class UndertowDestroyedContextTest : ServletTestSupport() {
    @Test
    fun `an undeployed application keeps its endpoints, leaves the module on, and a redeploy is counted`() {
        val first = Deployed("/undeployed/first")
        first.use {
            assertEquals(200, first.send())
            assertEquals(setOf("GET /undeployed/first", "POST /undeployed/first"), declared("/undeployed"))
            assertEquals(1, hits("GET", "/undeployed/first"))

            first.undeploy()
            ServletTestAgent.flush()

            assertEquals(emptyList(), registry.disabledModules())
            assertEquals(setOf("GET /undeployed/first", "POST /undeployed/first"), declared("/undeployed"))
        }

        Deployed("/undeployed/second").use { second ->
            assertEquals(200, second.send())
            assertEquals(emptyList(), registry.disabledModules())
            assertEquals(1, hits("GET", "/undeployed/second"))
            assertEquals(1, hits("GET", "/undeployed/first"))
        }
    }

    private class Deployed(
        private val mapping: String,
    ) : AutoCloseable {
        private val manager: DeploymentManager
        private val server: Undertow
        private val port: Int

        init {
            val info =
                ServletInfo("destroyed", GetPostServlet::class.java, ImmediateInstanceFactory(GetPostServlet())).apply {
                    isAsyncSupported = true
                    addMapping(mapping)
                }
            val deployment =
                Servlets
                    .deployment()
                    .setClassLoader(UndertowDestroyedContextTest::class.java.classLoader)
                    .setContextPath("/")
                    .setDeploymentName("destroyed" + System.nanoTime())
                    .addServlet(info)
            manager = Servlets.defaultContainer().addDeployment(deployment)
            manager.deploy()
            server =
                Undertow
                    .builder()
                    .addHttpListener(0, LOOPBACK)
                    .setHandler(Handlers.path().addPrefixPath("/", manager.start()))
                    .build()
            server.start()
            port = (server.listenerInfo.single().address as InetSocketAddress).port
        }

        fun send(): Int = sendHttp(port, "", mapping, "GET")

        fun undeploy() {
            manager.stop()
            manager.undeploy()
        }

        override fun close() = server.stop()
    }
}
