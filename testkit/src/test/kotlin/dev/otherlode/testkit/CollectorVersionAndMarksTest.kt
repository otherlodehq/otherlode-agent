package dev.otherlode.testkit

import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.otherlode.export.ProbeKind as WireProbeKind

class CollectorVersionAndMarksTest {
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        collector?.close()
    }

    private fun startAs(testkitVersion: String): OtherlodeTestCollector =
        OtherlodeTestCollector.startAsVersion(0, testkitVersion).also { collector = it }

    private fun resource(agentVersion: String) = ResourceAttributes("svc", null, "i-1", null, "run-1", agentVersion = agentVersion)

    private fun manifestBody(agentVersion: String): ByteArray =
        ProtoPayloadCodec.encode(
            ProbeManifest(resource(agentVersion), listOf(ProbeLocation(1, 0, WireProbeKind.METHOD, "com.acme.A", "m", "()V", 1, null))),
        )

    private fun post(
        target: OtherlodeTestCollector,
        path: String,
        body: ByteArray,
    ): Int =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create("${target.exportUrl}/v1/otherlode/$path"))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    @Test
    fun `a payload from an agent of the same version is accepted`() {
        val target = startAs("1.2.3")

        assertEquals(200, post(target, "manifest", manifestBody("1.2.3")))

        assertEquals(emptyList(), target.rejectedPayloads())
        assertEquals(listOf("m"), target.neverHit().map { it.methodName })
    }

    @Test
    fun `a payload from an agent of another version is rejected naming both versions, keeping nothing`() {
        val target = startAs("1.2.3")

        val statuses =
            listOf(
                post(target, "manifest", manifestBody("1.3.0")),
                post(target, "deltas", ProtoPayloadCodec.encode(DeltaBatch(resource("1.3.0"), emptyList()))),
            )

        assertEquals(listOf(400, 400), statuses)
        val reasons = target.rejectedPayloads()
        assertEquals(2, reasons.size)
        assertTrue(reasons.all { "1.3.0" in it && "1.2.3" in it && "use the testkit" in it }, "$reasons")
        assertFailsWith<IllegalStateException> { target.neverHit() }
    }

    @Test
    fun `a payload from an agent that does not know its version is accepted`() {
        val target = startAs("1.2.3")

        assertEquals(200, post(target, "manifest", manifestBody("")))
        assertEquals(emptyList(), target.rejectedPayloads())
    }

    @Test
    fun `a testkit that does not know its version accepts any agent version`() {
        val target = startAs("")

        assertEquals(200, post(target, "manifest", manifestBody("9.9.9")))
        assertEquals(emptyList(), target.rejectedPayloads())
    }

    @Test
    fun `a probe with no generated mark, routine or unread shape reads null for each`() {
        val target = OtherlodeTestCollector.start().also { collector = it }
        val exporter = HttpExporter(target.exportUrl)
        val unmarked = resource("")
        exporter.exportManifest(
            ProbeManifest(unmarked, listOf(ProbeLocation(1, 0, WireProbeKind.METHOD, "com.acme.A", "m", "()V", 1, null))),
        )
        exporter.exportDeltaBatch(DeltaBatch(unmarked, listOf(ProbeDelta(1, 0, WireProbeKind.METHOD, 1L, 0L))))

        val ref = target.neverHit().single()

        assertNull(ref.generatedBy)
        assertNull(ref.routine)
        assertNull(ref.unreadShape)
    }
}
