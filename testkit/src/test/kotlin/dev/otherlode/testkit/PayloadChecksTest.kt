package dev.otherlode.testkit

import dev.otherlode.export.ClassFailedToInitialize
import dev.otherlode.export.ClassListing
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.DependencyDelta
import dev.otherlode.export.DisabledEndpointModule
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDelta
import dev.otherlode.export.FailedClass
import dev.otherlode.export.FrameworkMarker
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.UnreportedClass
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadChecksTest {
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        collector?.close()
    }

    private fun start(version: String = ""): OtherlodeTestCollector =
        OtherlodeTestCollector.startAsVersion(0, version).also { collector = it }

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

    private fun resource(
        serviceName: String = "svc",
        instance: String = "i-1",
        runId: String = "run-1",
        namespace: String? = null,
        version: String? = null,
        agentVersion: String = "",
        fieldsStripped: Boolean = false,
    ) = ResourceAttributes(serviceName, version, instance, null, runId, namespace, agentVersion = agentVersion, fieldsStripped = fieldsStripped)

    private fun listing(
        resource: ResourceAttributes = resource(),
        listedAt: Long = 1000L,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
    ) = ProtoPayloadCodec.encode(ClassListing(resource, listOf("com.acme.A"), IncludeRules(listOf("com.acme")), listedAt, chunkIndex, chunkCount))

    private fun baseline(
        resource: ResourceAttributes = resource(),
        scannedAt: Long = 1L,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
        classes: List<DeclaredClass> = listOf(DeclaredClass("com.acme.Unloaded", listOf(DeclaredMethod("m", "()V")))),
    ) = ProtoPayloadCodec.encode(StaticBaseline(resource, classes, scannedAt = scannedAt, chunkIndex = chunkIndex, chunkCount = chunkCount))

    private fun deltas(
        resource: ResourceAttributes = resource(),
        firstSeenAt: Long = 1L,
    ) = ProtoPayloadCodec.encode(DeltaBatch(resource, listOf(ProbeDelta(1, 0, ProbeKind.METHOD, firstSeenAt, 1L))))

    private fun manifest(resource: ResourceAttributes = resource()) =
        ProtoPayloadCodec.encode(
            watchedManifest(resource, listOf(ProbeLocation(1, 0, ProbeKind.METHOD, "com.acme.A", "m", "()V", 1, null))),
        )

    private fun assertRejected(
        target: OtherlodeTestCollector,
        path: String,
        body: ByteArray,
        reason: String,
    ) {
        assertEquals(400, post(target, path, body))
        val reasons = target.rejectedPayloads()
        assertEquals(1, reasons.size, "$reasons")
        assertTrue(reason in reasons.single(), "wanted '$reason' in ${reasons.single()}")
        assertFailsWith<IllegalStateException> { target.neverHit() }
    }

    @Test
    fun `a class listing with fields stripped is rejected and fails a zero-count query`() {
        val target = start()

        assertRejected(target, "class-listing", listing(resource(fieldsStripped = true)), "stripped fields")
    }

    @Test
    fun `a class listing from another agent version is rejected`() {
        val target = start("1.2.3")

        assertRejected(target, "class-listing", listing(resource(agentVersion = "1.3.0")), "1.3.0")
    }

    @Test
    fun `a class listing with an empty run id is rejected`() {
        assertRejected(start(), "class-listing", listing(resource(runId = "")), "resource.run_id is empty")
    }

    @Test
    fun `a class listing with a second run id under one instance is rejected`() {
        val target = start()
        assertEquals(202, post(target, "class-listing", listing(resource(runId = "run-1"))))

        assertRejected(target, "class-listing", listing(resource(runId = "run-2")), "run-2")
    }

    @Test
    fun `a class listing from a second instance on a one-JVM collector is rejected`() {
        val target = OtherlodeTestCollector.startForOneJvm(0).also { collector = it }
        assertEquals(202, post(target, "class-listing", listing(resource(instance = "i-1"))))

        assertRejected(target, "class-listing", listing(resource(instance = "i-2")), "maxParallelForks")
    }

    @Test
    fun `a missing resource is rejected`() {
        assertRejected(start(), "deltas", byteArrayOf(), "service_name is empty")
    }

    @Test
    fun `a resource value over 1024 bytes is rejected on every payload`() {
        val long = "x".repeat(1025)
        for ((path, body) in listOf(
            "deltas" to deltas(resource(version = long)),
            "manifest" to manifest(resource(version = long)),
            "static-baseline" to baseline(resource(version = long)),
            "class-listing" to listing(resource(version = long)),
        )) {
            collector?.close()
            assertRejected(start(), path, body, "resource.service_version is 1025 bytes, over the 1024-byte limit")
        }
    }

    @Test
    fun `a resource value over 1024 bytes in UTF-8 is rejected though it is short in characters`() {
        assertRejected(start(), "deltas", deltas(resource(version = "é".repeat(600))), "resource.service_version is 1200 bytes")
    }

    @Test
    fun `a NUL byte in a resource value is rejected`() {
        assertRejected(start(), "deltas", deltas(resource(namespace = "a\u0000b")), "resource.service_namespace holds a NUL byte")
    }

    @Test
    fun `an empty service name is rejected`() {
        assertRejected(start(), "manifest", manifest(resource(serviceName = "  ")), "resource.service_name is empty")
    }

    @Test
    fun `a service name or namespace no URL path can name is rejected`() {
        assertRejected(start(), "deltas", deltas(resource(serviceName = "..")), "which no URL path can name")
        collector?.close()
        assertRejected(start(), "deltas", deltas(resource(serviceName = "svc", namespace = " . ")), "resource.service_namespace is \".\"")
    }

    @Test
    fun `an empty service instance id is rejected`() {
        assertRejected(start(), "deltas", deltas(resource(instance = "")), "resource.service_instance_id is empty")
    }

    @Test
    fun `a delta timestamp out of range is rejected`() {
        assertRejected(start(), "deltas", deltas(firstSeenAt = -1L), "deltas[0].first_seen_at is out of range")
        collector?.close()
        assertRejected(start(), "deltas", deltas(firstSeenAt = 253402300800000L), "deltas[0].first_seen_at is out of range")
    }

    @Test
    fun `an endpoint delta timestamp out of range is rejected`() {
        val body = ProtoPayloadCodec.encode(DeltaBatch(resource(), emptyList(), listOf(EndpointDelta(1, -5L, 1L))))

        assertRejected(start(), "deltas", body, "endpoint_deltas[0].first_seen_at is out of range")
    }

    @Test
    fun `a manifest timestamp out of range is rejected`() {
        val body =
            ProtoPayloadCodec.encode(
                watchedManifest(resource(), emptyList(), skippedClasses = listOf(SkippedClass("com.acme.A", "r", -1L))),
            )

        assertRejected(start(), "manifest", body, "skipped_classes[0].skipped_at is out of range")
    }

    @Test
    fun `a dependency delta timestamp out of range is rejected`() {
        val body = ProtoPayloadCodec.encode(DeltaBatch(resource(), emptyList(), dependencyDeltas = listOf(DependencyDelta(1, -1L, 1L))))

        assertRejected(start(), "deltas", body, "dependency_deltas[0].first_loaded_at is out of range")
    }

    @Test
    fun `every other manifest timestamp out of range is rejected`() {
        val base = watchedManifest(resource(), emptyList())
        val cases =
            listOf(
                base.copy(unreportedClasses = listOf(UnreportedClass("com.acme.A", -1L))) to
                    "unreported_classes[0].first_seen_unreported_at is out of range",
                base.copy(failedClasses = listOf(FailedClass("com.acme.A", -1L))) to "failed_classes[0].withheld_at is out of range",
                base.copy(
                    disabledEndpointModules = listOf(DisabledEndpointModule("jaxrs", "r", -1L, DisabledEndpointModuleKind.LINKAGE_ERROR)),
                ) to "disabled_endpoint_modules[0].disabled_at is out of range",
                base.copy(frameworkMarkers = listOf(FrameworkMarker("spring", listOf("org.example.A"), -1L))) to
                    "framework_markers[0].seen_at is out of range",
            )
        for ((manifest, reason) in cases) {
            val target = start()
            assertRejected(target, "manifest", ProtoPayloadCodec.encode(manifest), reason)
            target.close()
        }
    }

    @Test
    fun `a timestamp at either end of the range is accepted`() {
        val target = start()

        assertEquals(202, post(target, "deltas", deltas(firstSeenAt = 0L)))
        assertEquals(202, post(target, "deltas", deltas(firstSeenAt = 253402300799999L)))
        assertEquals(emptyList(), target.rejectedPayloads())
    }

    @Test
    fun `a service name of whitespace the collector trims is rejected, and one it keeps is accepted`() {
        val trimmed = start()
        assertRejected(trimmed, "manifest", manifest(resource(serviceName = "\u0085")), "resource.service_name is empty")
        trimmed.close()
        val target = start()
        assertEquals(202, post(target, "manifest", manifest(resource(serviceName = "\u001C"))))
    }

    @Test
    fun `a failed to initialize timestamp out of range is rejected`() {
        val body =
            ProtoPayloadCodec.encode(
                watchedManifest(
                    resource(),
                    emptyList(),
                    classesFailedToInitialize = listOf(ClassFailedToInitialize("com.acme.A", InitializationFailure.VERIFIER_REJECTED, -1L)),
                ),
            )

        assertRejected(start(), "manifest", body, "classes_failed_to_initialize[0].observed_at is out of range")
    }

    @Test
    fun `a manifest class failure with no reason is rejected`() {
        val proto =
            dev.otherlode.proto.ProbeManifest
                .newBuilder()
                .setResource(dev.otherlode.proto.ResourceAttributes.newBuilder().setServiceName("svc").setServiceInstanceId("i-1").setRunId("r"))
                .addClassesFailedToInitialize(dev.otherlode.proto.ClassFailedToInitialize.newBuilder().setClassName("com.acme.A"))
                .build()

        assertEquals(400, post(start(), "manifest", proto.toByteArray()))
        assertEquals(1, collector!!.rejectedPayloads().size)
    }

    @Test
    fun `a dependency with no identities is rejected`() {
        val proto =
            dev.otherlode.proto.ProbeManifest
                .newBuilder()
                .setResource(dev.otherlode.proto.ResourceAttributes.newBuilder().setServiceName("svc").setServiceInstanceId("i-1").setRunId("r"))
                .addDependencies(dev.otherlode.proto.DependencyLocation.newBuilder().setDependencyId(1))
                .build()

        assertEquals(400, post(start(), "manifest", proto.toByteArray()))
        assertEquals(1, collector!!.rejectedPayloads().size)
    }

    @Test
    fun `a static baseline with scanned_at not set is rejected`() {
        assertRejected(start(), "static-baseline", baseline(scannedAt = 0L), "scanned_at is not set")
    }

    @Test
    fun `a static baseline with a bad chunk count or index is rejected`() {
        val cases =
            listOf(
                baseline(chunkCount = 0) to "chunk_count must be at least 1",
                baseline(chunkIndex = -1, chunkCount = 2) to "chunk_index is negative",
                baseline(chunkIndex = 2, chunkCount = 2) to "chunk_index is out of range for chunk_count",
            )
        for ((body, reason) in cases) {
            collector?.close()
            assertRejected(start(), "static-baseline", body, reason)
        }
    }

    @Test
    fun `a class listing with listed_at not set is rejected`() {
        assertRejected(start(), "class-listing", listing(listedAt = 0L), "listed_at is not set")
    }

    @Test
    fun `a class listing with a bad chunk count or index is rejected`() {
        val cases =
            listOf(
                listing(chunkCount = 0) to "chunk_count must be at least 1",
                listing(chunkIndex = -1, chunkCount = 2) to "chunk_index is negative",
                listing(chunkIndex = 3, chunkCount = 3) to "chunk_index is out of range for chunk_count",
            )
        for ((body, reason) in cases) {
            collector?.close()
            assertRejected(start(), "class-listing", body, reason)
        }
    }

    @Test
    fun `a valid payload of each kind is accepted`() {
        val target = start()

        assertEquals(202, post(target, "deltas", deltas()))
        assertEquals(202, post(target, "manifest", manifest()))
        assertEquals(202, post(target, "static-baseline", baseline()))
        assertEquals(202, post(target, "class-listing", listing()))
        assertEquals(202, post(target, "deltas", deltas(resource(namespace = "ns", version = "x".repeat(1024)))))

        assertEquals(emptyList(), target.rejectedPayloads())
        assertEquals(listOf("com.acme.Unloaded"), target.neverLoaded())
    }

    private fun scanComplete(
        target: OtherlodeTestCollector,
        vararg chunks: Pair<Int, Int>,
    ): Boolean {
        assertEquals(202, post(target, "deltas", deltas()))
        for ((index, count) in chunks) {
            assertEquals(202, post(target, "static-baseline", baseline(chunkIndex = index, chunkCount = count)))
        }
        return try {
            target.neverLoaded()
            true
        } catch (e: IllegalStateException) {
            assertTrue("no complete static baseline scan" in e.message.orEmpty(), "${e.message}")
            false
        }
    }

    @Test
    fun `a chunk index outside the scan's count is rejected and does not complete it`() {
        val target = start()
        assertEquals(202, post(target, "static-baseline", baseline(chunkIndex = 0, chunkCount = 2)))

        assertEquals(400, post(target, "static-baseline", baseline(chunkIndex = 5, chunkCount = 2)))
    }

    @Test
    fun `two chunks of count 2 complete the scan`() {
        assertTrue(scanComplete(start(), 0 to 2, 1 to 2))
    }

    @Test
    fun `a chunk with another count does not complete the scan`() {
        assertEquals(false, scanComplete(start(), 0 to 2, 1 to 3))
    }

    @Test
    fun `a chunk with another count is ignored when it arrives second`() {
        assertEquals(false, scanComplete(start(), 1 to 2, 0 to 3))
    }
}
