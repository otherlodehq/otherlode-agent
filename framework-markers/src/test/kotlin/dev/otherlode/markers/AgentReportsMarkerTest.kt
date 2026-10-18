package dev.otherlode.markers

import dev.otherlode.registry.FrameworkMarkers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent attached to a JVM that serves reports the marker on its manifest, with the class set that
 * matched; attached to a JVM that does not serve, it reports none. The marker reaches the manifest
 * through the loaded-class sweep, here the final flush's, when the JVM exits.
 */
class AgentReportsMarkerTest {
    private val agentJar = ForkedJvm.property("otherlode.agent.shadedJar")

    private fun reportedBy(
        framework: String,
        version: String,
        mainClass: String,
        mode: String,
    ): List<dev.otherlode.export.FrameworkMarker> =
        AgentReceiver().use { receiver ->
            val agent =
                "$agentJar=serviceName=markers,includePackages=markers,flushIntervalSeconds=1,exportUrl=${receiver.endpoint}"
            ForkedJvm.run(ForkedJvm.classpath(framework, version), mainClass, mode, javaagent = agent)
            assertTrue(receiver.failures.isEmpty(), "payloads decode: ${receiver.failures}")
            assertTrue(receiver.manifests.isNotEmpty(), "the agent sent a manifest")
            receiver.manifests.flatMap { it.frameworkMarkers }
        }

    private fun classesOf(markerId: String) =
        FrameworkMarkers.ALL
            .single { it.id == markerId }
            .classSets
            .single()

    @Test
    fun `a serving gRPC JVM's manifest carries the gRPC marker with its class set`() {
        val markers = reportedBy("grpc", "1.85.0", "markers.GrpcMain", "serving")

        val marker = markers.single()
        assertEquals("grpc-server", marker.id)
        assertEquals(classesOf("grpc-server"), marker.classes)
        assertTrue(marker.seenAt > 0)
    }

    @Test
    fun `a gRPC client-only JVM's manifests carry no marker`() {
        assertEquals(emptyList(), reportedBy("grpc", "1.85.0", "markers.GrpcMain", "client"))
    }

    @Test
    fun `a JVM that configures Undertow's routing handler reports the Undertow marker`() {
        val markers = reportedBy("undertow", "2.4.1.Final", "markers.UndertowMain", "serving")

        assertEquals(listOf("undertow-routing-handler"), markers.map { it.id })
        assertEquals(classesOf("undertow-routing-handler"), markers.single().classes)
    }

    @Test
    fun `an Undertow JVM serving through a plain handler reports no marker`() {
        assertEquals(emptyList(), reportedBy("undertow", "2.4.1.Final", "markers.UndertowMain", "baseline"))
    }

    @Test
    fun `a serving Play JVM's manifest carries the Play marker with the backend that matched`() {
        val markers = reportedBy("play-pekko", "3.0.12", "markers.PlayMain", "serving")

        val marker = markers.single()
        assertEquals("play-server", marker.id)
        assertEquals(listOf("play.core.server.PekkoHttpServer"), marker.classes)
    }

    @Test
    fun `a Play JVM that only uses the framework's helpers reports no marker`() {
        assertEquals(emptyList(), reportedBy("play-pekko", "3.0.12", "markers.PlayMain", "baseline"))
    }

    @Test
    fun `a serving Struts 2 JVM's manifest carries the Struts marker`() {
        val markers = reportedBy("struts", "6.12.0", "markers.StrutsMain", "serving")

        assertEquals(listOf("struts2-dispatcher"), markers.map { it.id })
        assertEquals(listOf("org.apache.struts2.dispatcher.ExecuteOperations"), markers.single().classes)
    }
}
