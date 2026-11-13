package dev.otherlode.testkit

import dev.otherlode.export.CallEdge
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.UnreportedClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkippedClassTest {
    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun resource(instance: String) = ResourceAttributes("svc", null, instance, null, "run-$instance")

    private fun method(
        classId: Int,
        index: Int,
        className: String,
        methodName: String,
        calls: List<CallEdge> = emptyList(),
    ) = ProbeLocation(classId, index, ProbeKind.METHOD, className, methodName, "()V", 1, null, calls = calls)

    private fun call(
        className: String,
        methodName: String,
    ) = CallEdge(className, methodName, "()V", false)

    private fun omission(
        classId: Int,
        index: Int,
        className: String,
        parameterIndex: Int,
    ) = ProbeLocation(
        classId,
        index,
        ProbeKind.OPTIONAL_ARGUMENT,
        className,
        "f",
        "()V",
        1,
        null,
        parameterIndex = parameterIndex,
        parameterName = "p$parameterIndex",
    )

    private fun manifest(
        instance: String,
        probes: List<ProbeLocation>,
        skipped: List<String> = emptyList(),
        unreported: List<String> = emptyList(),
    ) {
        exporter.exportManifest(
            watchedManifest(
                resource(instance),
                probes,
                skippedClasses = skipped.map { SkippedClass(it, "too large", 1L) },
                unreportedClasses = unreported.map { UnreportedClass(it, 1L) },
            ),
        )
        exporter.exportDeltaBatch(DeltaBatch(resource(instance), emptyList()))
    }

    private fun hits(
        instance: String,
        vararg hit: Pair<Int, Int>,
    ) {
        exporter.exportDeltaBatch(
            DeltaBatch(resource(instance), hit.map { (classId, index) -> ProbeDelta(classId, index, ProbeKind.METHOD, 1L, 5L) }),
        )
    }

    private fun baseline(
        instance: String,
        vararg classes: DeclaredClass,
    ) {
        exporter.exportStaticBaseline(StaticBaseline(resource(instance), classes.toList(), scannedAt = 1000L))
    }

    private fun mentions(
        cluster: UnreachedCluster,
        className: String,
    ) = cluster.root.className == className ||
        cluster.methods.any { it.className == className } ||
        cluster.wholeClasses.any { it.className == className }

    private fun probesOfShapedClass(
        classId: Int,
        className: String,
    ) = listOf(
        method(classId, 0, className, "<clinit>"),
        method(classId, 1, className, "m"),
        method(classId, 2, className, "f"),
        omission(classId, 3, className, 0),
        omission(classId, 4, className, 1),
    )

    private fun plainShape(
        classId: Int,
        className: String,
    ) = listOf(method(classId, 0, className, "k"), method(classId, 1, className, "m"))

    private fun shapedScenario(skipped: List<String>) {
        manifest(
            "i-1",
            probesOfShapedClass(1, "com.acme.X") +
                probesOfShapedClass(2, "com.acme.Y") +
                plainShape(3, "com.acme.P") +
                plainShape(4, "com.acme.R"),
        )
        exporter.exportDeltaBatch(
            DeltaBatch(
                resource("i-1"),
                listOf(
                    ProbeDelta(1, 2, ProbeKind.METHOD, 1L, 5L),
                    ProbeDelta(1, 3, ProbeKind.OPTIONAL_ARGUMENT, 1L, 5L),
                    ProbeDelta(2, 2, ProbeKind.METHOD, 1L, 5L),
                    ProbeDelta(2, 3, ProbeKind.OPTIONAL_ARGUMENT, 1L, 5L),
                    ProbeDelta(3, 0, ProbeKind.METHOD, 1L, 1L),
                    ProbeDelta(4, 0, ProbeKind.METHOD, 1L, 1L),
                ),
            ),
        )
        manifest("i-2", emptyList(), skipped = skipped)
    }

    @Test
    fun `a class another instance skipped has no never-hit row, class finding or supplied row`() {
        shapedScenario(skipped = listOf("com.acme.X", "com.acme.P"))

        assertEquals(listOf("com.acme.R"), collector.neverHit().map { it.className })
        assertEquals(listOf("com.acme.Y"), collector.neverInitialized().map { it.className })
        assertEquals(emptyList(), collector.neverInstantiated())
        assertEquals(listOf("com.acme.Y"), collector.neverSupplied().map { it.className })
        assertEquals(listOf("com.acme.Y"), collector.alwaysSupplied().map { it.className })
        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { mentions(it, "com.acme.X") || mentions(it, "com.acme.P") }, clusters.toString())
        assertTrue(clusters.any { mentions(it, "com.acme.R") }, clusters.toString())
    }

    @Test
    fun `a class no instance skipped is judged as before`() {
        shapedScenario(skipped = emptyList())

        assertEquals(listOf("com.acme.P", "com.acme.R"), collector.neverHit().map { it.className })
        assertEquals(listOf("com.acme.X", "com.acme.Y"), collector.neverInitialized().map { it.className })
        assertEquals(listOf("com.acme.X", "com.acme.Y"), collector.neverSupplied().map { it.className })
        assertEquals(listOf("com.acme.X", "com.acme.Y"), collector.alwaysSupplied().map { it.className })
        val clusters = collector.unreachedClusters()
        assertTrue(clusters.any { mentions(it, "com.acme.P") }, clusters.toString())
    }

    @Test
    fun `a class the only instance skipped supports no claim`() {
        manifest("i-1", listOf(method(1, 0, "com.acme.H", "go")), skipped = listOf("com.acme.X"))
        hits("i-1", 1 to 0)

        assertTrue(collector.neverHit().none { it.className == "com.acme.X" })
        assertEquals(emptyList(), collector.neverInitialized())
        assertEquals(emptyList(), collector.neverSupplied())
        assertEquals(emptyList(), collector.alwaysSupplied())
        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a never-hit method of a skipped class blocks its callee`() {
        manifest(
            "i-1",
            listOf(
                method(1, 0, "com.acme.X", "m", calls = listOf(call("com.acme.T", "a"))),
                method(2, 0, "com.acme.T", "a"),
                method(3, 0, "com.acme.H", "go"),
            ),
        )
        hits("i-1", 3 to 0)
        manifest("i-2", emptyList(), skipped = listOf("com.acme.X"))

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { mentions(it, "com.acme.X") || mentions(it, "com.acme.T") }, clusters.toString())
    }

    private fun blockedByDeclaredCallerScenario(
        skipped: List<String>,
        unreported: List<String>,
    ) {
        manifest(
            "i-1",
            listOf(
                method(1, 0, "com.acme.T", "a"),
                method(2, 0, "com.acme.U", "x", calls = listOf(call("com.acme.T", "a"))),
                method(3, 0, "com.acme.H", "go"),
            ),
            skipped = skipped,
            unreported = unreported,
        )
        hits("i-1", 3 to 0)
    }

    private fun declaredS() =
        DeclaredClass("com.acme.S", listOf(DeclaredMethod("go", "()V", calls = listOf(call("com.acme.T", "a")))))

    @Test
    fun `a declared method of a skipped class blocks its callee`() {
        blockedByDeclaredCallerScenario(skipped = listOf("com.acme.S"), unreported = emptyList())
        baseline("i-1", declaredS())

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { it.root.className == "com.acme.T" }, clusters.toString())
        val uCluster = clusters.single { it.root.className == "com.acme.U" }
        assertTrue(!mentions(uCluster, "com.acme.T"), uCluster.toString())
    }

    @Test
    fun `a declared method of an unreported class blocks its callee`() {
        blockedByDeclaredCallerScenario(skipped = emptyList(), unreported = listOf("com.acme.S"))
        baseline("i-1", declaredS())

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { it.root.className == "com.acme.T" }, clusters.toString())
        val uCluster = clusters.single { it.root.className == "com.acme.U" }
        assertTrue(!mentions(uCluster, "com.acme.T"), uCluster.toString())
    }

    @Test
    fun `a declared method of a loaded class with no method probe blocks its callee`() {
        manifest(
            "i-1",
            listOf(method(1, 0, "com.acme.T", "a"), method(2, 0, "com.acme.L", "known"), method(3, 0, "com.acme.H", "go")),
        )
        hits("i-1", 2 to 0, 3 to 0)
        baseline(
            "i-1",
            DeclaredClass(
                "com.acme.L",
                listOf(
                    DeclaredMethod("known", "()V"),
                    DeclaredMethod("extra", "()V", calls = listOf(call("com.acme.T", "a"))),
                ),
            ),
        )

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { mentions(it, "com.acme.T") }, clusters.toString())
    }

    private fun sendWithHits(
        instance: String,
        probes: List<ProbeLocation>,
        vararg hit: Pair<Int, Int>,
        skipped: List<String> = emptyList(),
    ) {
        manifest(instance, probes, skipped = skipped)
        val kinds = probes.associate { (it.classId to it.probeIndex) to it.kind }
        exporter.exportDeltaBatch(
            DeltaBatch(resource(instance), hit.map { (classId, index) -> ProbeDelta(classId, index, kinds.getValue(classId to index), 1L, 5L) }),
        )
    }

    private fun branch(
        classId: Int,
        index: Int,
        className: String,
        methodName: String,
        branchIndex: Int,
    ) = ProbeLocation(classId, index, ProbeKind.BRANCH, className, methodName, "()V", 3, branchIndex, siteIndex = 0)

    @Test
    fun `a never-hit lambda whose only creator is a never-hit method of a skipped class stays a never-hit row`() {
        val probes =
            listOf(
                method(1, 0, "com.acme.X", "m", calls = listOf(CallEdge("com.acme.Y", "lambda\$0", "()V", false, kind = CallEdgeKind.CREATES))),
                ProbeLocation(2, 0, ProbeKind.METHOD, "com.acme.Y", "lambda\$0", "()V", 1, null, static = true, lambdaBody = true),
                method(2, 1, "com.acme.Y", "k"),
            )
        sendWithHits("i-1", probes, 2 to 1)
        assertEquals(listOf("com.acme.X" to "m"), collector.neverHit().map { it.className to it.methodName })

        manifest("i-2", emptyList(), skipped = listOf("com.acme.X"))

        assertEquals(listOf("com.acme.Y" to "lambda\$0"), collector.neverHit().map { it.className to it.methodName })
    }

    @Test
    fun `a call guarded by a never-hit outcome of a skipped class roots nothing`() {
        val probes =
            listOf(
                method(1, 0, "com.acme.X", "handle", calls = listOf(CallEdge("com.acme.T", "a", "()V", false, guard = 1))),
                branch(1, 1, "com.acme.X", "handle", 0),
                branch(1, 2, "com.acme.X", "handle", 1),
                method(2, 0, "com.acme.T", "a"),
            )
        sendWithHits("i-1", probes, 1 to 0, 1 to 1)
        assertEquals(RootKind.UNTAKEN_OUTCOME, collector.unreachedClusters().single().rootKind)

        manifest("i-2", emptyList(), skipped = listOf("com.acme.X"))

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { mentions(it, "com.acme.T") || mentions(it, "com.acme.X") }, clusters.toString())
    }

    @Test
    fun `a hit method of a skipped class is still a hit caller`() {
        sendWithHits("i-1", listOf(method(1, 0, "com.acme.X", "go", calls = listOf(call("com.acme.T", "a"))), method(2, 0, "com.acme.T", "a")), 1 to 0)
        manifest("i-2", emptyList(), skipped = listOf("com.acme.X"))

        val cluster = collector.unreachedClusters().single()
        assertEquals("com.acme.T" to "a", cluster.root.className to cluster.root.methodName)
        assertEquals(RootKind.REACHED_FROM_HIT, cluster.rootKind)
    }

    @Test
    fun `a class one instance both probed and skipped gets no never-hit row`() {
        sendWithHits("i-1", listOf(method(1, 0, "com.acme.X", "m"), method(2, 0, "com.acme.Z", "k")), 2 to 0, skipped = listOf("com.acme.X"))

        assertTrue(collector.neverHit().none { it.className == "com.acme.X" }, collector.neverHit().toString())
    }

    @Test
    fun `an optional parameter whose omission probe sits in a skipped class is in neither supplied list`() {
        val probes =
            listOf(
                method(1, 0, "com.acme.Tgt", "f"),
                ProbeLocation(
                    2,
                    0,
                    ProbeKind.OPTIONAL_ARGUMENT,
                    "com.acme.Caller",
                    "f",
                    "()V",
                    1,
                    null,
                    parameterIndex = 0,
                    parameterName = "p0",
                    targetClassName = "com.acme.Tgt",
                ),
            )
        sendWithHits("i-1", probes, 1 to 0)
        assertEquals(listOf("com.acme.Tgt"), collector.alwaysSupplied().map { it.className })

        manifest("i-2", emptyList(), skipped = listOf("com.acme.Caller"))

        assertEquals(emptyList(), collector.alwaysSupplied())
        assertEquals(emptyList(), collector.neverSupplied())
    }
}
