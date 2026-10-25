package dev.otherlode.export

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.otherlode.proto.ClassListing as ProtoClassListing
import dev.otherlode.proto.DeltaBatch as ProtoDeltaBatch
import dev.otherlode.proto.ProbeManifest as ProtoProbeManifest
import dev.otherlode.proto.StaticBaseline as ProtoStaticBaseline

class PayloadWeightTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1", flushIntervalSeconds = 30)
    private val random = Random(20261024)

    private fun text(length: Int) = String(CharArray(length) { 'a' + random.nextInt(26) })

    private fun longLiteral() = "x".repeat(65_535)

    private fun site(
        outcomes: Int,
        literal: String,
    ) = BranchSite(
        siteIndex = random.nextInt(1000),
        siteKey = text(8),
        line = random.nextInt(5000),
        outcomes =
            List(outcomes) {
                BranchOutcome(
                    it,
                    BranchRole.CASE,
                    caseKey = random.nextInt(),
                    guardedLines = List(random.nextInt(3)) { LineRange("A.kt", 1, 9) },
                    partlyGuardedLines = List(random.nextInt(3)) { LineRange("A.kt", 2, 4) },
                    caseLabel = listOf(ConditionPart(ConditionPartKind.STRING_LITERAL, literal)),
                )
            },
        guard = random.nextInt(50),
        condition =
            listOf(
                ConditionPart(ConditionPartKind.CODE, "x == "),
                ConditionPart(ConditionPartKind.STRING_LITERAL, literal),
            ),
    )

    private fun edges(count: Int) =
        List(count) { CallEdge("com.acme.${text(12)}", text(6), "(I)V", random.nextBoolean(), capturedCount = it) }

    private fun deltas(count: Int) =
        List(count) {
            ProbeDelta(
                random.nextInt(100_000),
                random.nextInt(100_000),
                ProbeKind.BRANCH,
                random.nextLong(1, Long.MAX_VALUE),
                random.nextLong(1, Long.MAX_VALUE),
            )
        }

    private fun locations(literal: String) =
        List(6) { i ->
            ProbeLocation(
                classId = i * 1000,
                probeIndex = i,
                kind = ProbeKind.BRANCH,
                className = "com.acme.${text(40)}",
                methodName = text(10),
                methodDescriptor = "(Ljava/lang/String;)V",
                line = i,
                branchIndex = i,
                calls = edges(i),
                referencedClasses = List(i) { "com.acme.${text(9)}" },
                branchSites = listOf(site(i + 1, literal)),
                parameterNames = List(i) { text(5) },
                outsideCaller = if (i % 2 == 0) OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, "com.acme.Base") else null,
            )
        }

    private fun elementsOf(message: Message): Long {
        var count = 0L
        for (field in message.descriptorForType.fields) {
            val message1 = field.javaType == FieldDescriptor.JavaType.MESSAGE
            if (field.isRepeated) {
                val size = message.getRepeatedFieldCount(field)
                count += size
                if (message1) for (i in 0 until size) count += elementsOf(message.getRepeatedField(field, i) as Message)
            } else if (message1 && message.hasField(field)) {
                count += 1 + elementsOf(message.getField(field) as Message)
            }
        }
        return count
    }

    private fun assertBounds(
        real: ByteArray,
        parsed: Message,
        sum: PayloadWeight,
        slackBytes: Long = SLACK_BYTES.toLong(),
    ) {
        val realElements = elementsOf(parsed)
        assertTrue(sum.bytes >= real.size, "summed bytes ${sum.bytes} are below the encoded size ${real.size}")
        assertTrue(
            sum.bytes - real.size <= slackBytes,
            "summed bytes ${sum.bytes} exceed the encoded size ${real.size} by more than $slackBytes",
        )
        assertEquals(realElements, sum.elements)
    }

    @Test
    fun `a delta batch weighs at least its encoding, and its elements are exact`() {
        val ds = deltas(300)
        val empty = DeltaBatch(resource, emptyList())
        val full =
            DeltaBatch(
                resource,
                ds,
                endpointDeltas = List(40) { EndpointDelta(it, 1_700_000_000_000L + it, Long.MAX_VALUE - it) },
                dependencyDeltas = List(25) { DependencyDelta(it, 1_700_000_000_000L, 1L shl 40) },
                payloadSequence = 77,
                countsPendingSince = 1_700_000_000_000L,
            )
        val sum =
            ProtoPayloadCodec.weightOf(empty) +
                ds.fold(PayloadWeight.ZERO) { a, d -> a + ProtoPayloadCodec.entryWeight(d) } +
                full.endpointDeltas.fold(PayloadWeight.ZERO) { a, d -> a + ProtoPayloadCodec.entryWeight(d) } +
                full.dependencyDeltas.fold(PayloadWeight.ZERO) { a, d -> a + ProtoPayloadCodec.entryWeight(d) }

        val real = ProtoPayloadCodec.encode(full)

        assertBounds(real, ProtoDeltaBatch.parseFrom(real), sum, slackBytes = Long.MAX_VALUE)
    }

    @Test
    fun `a manifest weighs at least its encoding, with long literals, long names and every rider kind`() {
        val probes = locations(longLiteral())
        val longName = "com.acme." + "N".repeat(65_000)
        val manifest =
            ProbeManifest(
                resource,
                probes,
                skippedClasses = listOf(SkippedClass(longName, "too large", 5L)),
                endpoints =
                    listOf(
                        EndpointLocation(3, "GET", "/a/{id}", "/a/*", "spring", EndpointDiscoverySource.REGISTRATION, "H", "m", "()V"),
                    ),
                disabledEndpointModules = listOf(DisabledEndpointModule("m", "r", 9L, DisabledEndpointModuleKind.LINKAGE_ERROR)),
                classLocations =
                    listOf(
                        ClassLocation(1, "com.acme.Base", listOf("a.B", "c.D"), "A.kt", BodyKind.NONE, null, KotlinKind.KOTLIN_CLASS),
                    ),
                unreportedClasses = listOf(UnreportedClass(longName, 123L)),
                dependencies =
                    listOf(
                        DependencyLocation(
                            4,
                            listOf(DependencyIdentity("g", "a", "1"), DependencyIdentity(null, "b", null)),
                            DependencyIdentitySource.POM_PROPERTIES,
                            "/lib/a.jar",
                            DependencyDiscoverySource.LOAD,
                            classCount = 7,
                        ),
                    ),
                classReferences = listOf(ClassReferences(1, listOf("a.B", "c.D", longName))),
                externalClasses = listOf(ExternalClass("e.F", 4), ExternalClass("g.H", null, absent = true)),
                failedClasses = listOf(FailedClass(longName, 55L)),
                classesFailedToInitialize = listOf(ClassFailedToInitialize("x.Y", InitializationFailure.VERIFIER_REJECTED, 8L)),
                frameworkMarkers = listOf(FrameworkMarker("spring", listOf("a.B", "c.D"), 12L)),
                payloadSequence = 1L shl 50,
                countsPendingSince = 1_700_000_000_000L,
                unplacedVerificationFailures = 1L shl 45,
                referencesRecorded = true,
                dependenciesListed = true,
                verifierRejectionsWatched = true,
            )
        val codec = ProtoPayloadCodec
        val sum =
            codec.weightOf(ProbeManifest(resource, emptyList())) +
                manifest.probes.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.skippedClasses.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.endpoints.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.disabledEndpointModules.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.classLocations.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.unreportedClasses.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.dependencies.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.classReferences.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.externalClasses.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.failedClasses.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.classesFailedToInitialize.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                manifest.frameworkMarkers.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) }

        val real = codec.encode(manifest)

        assertTrue(real.size > 6 * 2 * 65_535, "the payload must be dominated by the long literals")
        assertBounds(real, ProtoProbeManifest.parseFrom(real), sum)
    }

    @Test
    fun `a static baseline weighs at least its encoding, with long literals and nested sites`() {
        val literal = longLiteral()
        val classes =
            List(5) { i ->
                DeclaredClass(
                    "com.acme.${text(30)}",
                    List(i + 1) { m ->
                        DeclaredMethod(
                            "m$m",
                            "()V",
                            calls = edges(m),
                            referencedClasses = List(m) { "com.acme.${text(7)}" },
                            branchSites = listOf(site(m + 1, literal)),
                            parameterNames = List(m) { text(4) },
                        )
                    },
                    superClassName = "java.lang.Object",
                    interfaceNames = listOf("java.io.Serializable"),
                    referencedClasses = List(i) { "com.acme.${text(11)}" },
                    sourceFile = "A.kt",
                )
            }
        val unreadable = List(3) { UnreadableClass("com.acme.U$it", "truncated") }
        val unprobed = List(3) { UnprobedClass("com.acme.P$it", "interface") }
        val rules = IncludeRules(listOf("com.acme", "org.acme"), listOf("com.acme.internal"))
        val baseline =
            StaticBaseline(
                resource,
                classes,
                unreadable,
                unprobed,
                scannedAt = 1_700_000_000_000L,
                chunkIndex = 2,
                chunkCount = 9,
                includeRules = rules,
            )
        val codec = ProtoPayloadCodec
        val sum =
            codec.weightOf(StaticBaseline(resource, emptyList(), scannedAt = 1_700_000_000_000L, includeRules = rules)) +
                classes.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                unreadable.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) } +
                unprobed.fold(PayloadWeight.ZERO) { a, e -> a + codec.entryWeight(e) }

        val real = codec.encode(baseline)

        assertTrue(real.size > 15 * 2 * 65_535)
        assertBounds(real, ProtoStaticBaseline.parseFrom(real), sum)
    }

    @Test
    fun `a class listing weighs at least its encoding, with names of the longest length`() {
        val names = List(50) { "com.acme.${text(random.nextInt(1, 60))}" } + "n.".padEnd(65_535, 'N')
        val rules = IncludeRules(listOf("com.acme"), listOf("com.acme.internal"))
        val listing = ClassListing(resource, names, rules, listedAt = 1_700_000_000_000L, chunkIndex = 3, chunkCount = 4)
        val codec = ProtoPayloadCodec
        val sum =
            codec.weightOf(ClassListing(resource, emptyList(), rules, 1_700_000_000_000L)) +
                names.fold(PayloadWeight.ZERO) { a, n -> a + codec.classNameWeight(n) }

        val real = codec.encode(listing)

        assertBounds(real, ProtoClassListing.parseFrom(real), sum)
    }

    @Test
    fun `a repeated string field counts one element per value`() {
        val manifest = ProbeManifest(resource, emptyList(), frameworkMarkers = listOf(FrameworkMarker("m", List(10) { "c$it" }, 1L)))

        val weight = ProtoPayloadCodec.entryWeight(manifest.frameworkMarkers.single())

        assertEquals(1L + 10L, weight.elements)
    }

    @Test
    fun `a delta's weight covers its widest encoding`() {
        val probe = ProbeDelta(Int.MIN_VALUE, Int.MIN_VALUE, ProbeKind.BRANCH, Long.MIN_VALUE, Long.MIN_VALUE)
        val endpoint = EndpointDelta(Int.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
        val dependency = DependencyDelta(Int.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
        val batch = DeltaBatch(resource, listOf(probe), endpointDeltas = listOf(endpoint), dependencyDeltas = listOf(dependency))
        val empty = ProtoPayloadCodec.encode(DeltaBatch(resource, emptyList())).size

        val real = ProtoPayloadCodec.encode(batch).size - empty
        val summed =
            ProtoPayloadCodec.entryWeight(probe).bytes + ProtoPayloadCodec.entryWeight(endpoint).bytes +
                ProtoPayloadCodec.entryWeight(dependency).bytes

        assertTrue(summed >= real, "summed $summed, real $real")
    }

    @Test
    fun `a small delta batch counts the resource and each delta as one element`() {
        val batch =
            DeltaBatch(
                resource,
                listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 2L), ProbeDelta(1, 1, ProbeKind.METHOD, 1L, 3L)),
            )

        assertEquals(3L, ProtoPayloadCodec.weightOf(batch).elements)
    }

    @Test
    fun `a chunk is admitted up to 8 MiB and up to 90 percent of the collector's element limit`() {
        val kinds = PayloadKind.entries
        assertEquals(listOf(450_000L, 90_000L, 180_000L, 90_000L), kinds.map { it.maxElements })
        for (kind in kinds) {
            assertNull(kind.exceededBound(PayloadWeight(ChunkLimits.MAX_ENCODED_BYTES, kind.maxElements)))
            assertEquals(ChunkBound.ENCODED_BYTES, kind.exceededBound(PayloadWeight(ChunkLimits.MAX_ENCODED_BYTES + 1, 1)))
            assertNotNull(kind.exceededBound(PayloadWeight(1, kind.maxElements + 1)))
        }
    }

    private companion object {
        const val SLACK_BYTES = 64L
    }
}
