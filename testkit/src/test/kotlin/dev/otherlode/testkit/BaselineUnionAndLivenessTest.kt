package dev.otherlode.testkit

import dev.otherlode.export.CallEdge
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.DependencyDelta
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.export.DependencyLocation
import dev.otherlode.export.ExternalClass
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.StaticBaseline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineUnionAndLivenessTest {
    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun resource(instanceId: String) = ResourceAttributes("svc", null, instanceId, null, "run-$instanceId")

    private val lib =
        DependencyLocation(
            dependencyId = 0,
            identities = listOf(DependencyIdentity("org.example", "lib", "1.0")),
            identitySource = DependencyIdentitySource.POM_PROPERTIES,
            location = "/libs/lib.jar",
            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 10,
        )

    private fun method(
        classId: Int,
        index: Int,
        className: String,
        methodName: String,
        calls: List<CallEdge> = emptyList(),
    ) = ProbeLocation(classId, index, ProbeKind.METHOD, className, methodName, "()V", 1, null, calls = calls)

    private fun manifest(
        instanceId: String,
        probes: List<ProbeLocation> = emptyList(),
        recording: Boolean = true,
        skipped: List<SkippedClass> = emptyList(),
        withLib: Boolean = recording,
    ) {
        exporter.exportManifest(
            watchedManifest(
                resource = resource(instanceId),
                probes = probes,
                skippedClasses = skipped,
                dependencies = if (withLib) listOf(lib) else emptyList(),
                externalClasses = if (recording) listOf(ExternalClass("org.example.Lib", 0)) else emptyList(),
                referencesRecorded = recording,
                dependenciesListed = true,
            ),
        )
        if (withLib) exporter.exportDeltaBatch(DeltaBatch(resource(instanceId), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L))))
    }

    private fun hit(
        instanceId: String,
        classId: Int,
        index: Int,
    ) = exporter.exportDeltaBatch(DeltaBatch(resource(instanceId), listOf(ProbeDelta(classId, index, ProbeKind.METHOD, 1L, 3L))))

    private fun baseline(
        instanceId: String,
        classes: List<DeclaredClass>,
        scannedAt: Long = 1000L,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
        externals: List<ExternalClass> = listOf(ExternalClass("org.example.Lib", 0)),
    ) = exporter.exportStaticBaseline(
        StaticBaseline(
            resource = resource(instanceId),
            declaredClasses = classes,
            scannedAt = scannedAt,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            externalClasses = externals,
        ),
    )

    private val libReference = listOf("org.example.Lib")

    private fun status() = collector.dependency("org.example", "lib").status

    @Test
    fun `a class-level reference of a skipped class is live, since the class loaded`() {
        manifest("i-1", skipped = listOf(SkippedClass("demo.Skipped", "unsafe annotation", 1L)))
        baseline("i-1", listOf(DeclaredClass("demo.Skipped", listOf(DeclaredMethod("run", "()V")), referencedClasses = libReference)))

        assertEquals(DependencyUsage.USED, status())
    }

    @Test
    fun `a class-level reference of a class no instance loaded is unreached`() {
        manifest("i-1")
        baseline("i-1", listOf(DeclaredClass("demo.Gone", listOf(DeclaredMethod("run", "()V")), referencedClasses = libReference)))

        assertEquals(DependencyUsage.UNREACHED, status())
        assertTrue(collector.dependency("org.example", "lib").sites.single().neverLoaded)
    }

    @Test
    fun `a class-level reference is live when another instance loaded the class`() {
        manifest("i-1")
        manifest("i-2", recording = false, probes = listOf(method(1, 0, "demo.Shared", "run")))
        baseline("i-1", listOf(DeclaredClass("demo.Shared", listOf(DeclaredMethod("run", "()V")), referencedClasses = libReference)))

        assertEquals(DependencyUsage.USED, status())
    }

    @Test
    fun `a method reference of a skipped class has no evidence, as no probe can hit it`() {
        manifest("i-1", skipped = listOf(SkippedClass("demo.Skipped", "unsafe annotation", 1L)))
        baseline("i-1", listOf(DeclaredClass("demo.Skipped", listOf(DeclaredMethod("run", "()V", referencedClasses = libReference)))))

        assertEquals(DependencyUsage.WITHOUT_EVIDENCE, status())
        assertEquals(emptyList(), collector.unreachedDependencies())
    }

    @Test
    fun `a baseline method is live when an instance that records no references hit it`() {
        manifest("i-1", recording = false, probes = listOf(method(1, 0, "demo.C", "m")))
        hit("i-1", 1, 0)
        manifest("i-2")
        baseline("i-2", listOf(DeclaredClass("demo.C", listOf(DeclaredMethod("m", "()V", referencedClasses = libReference)))))

        assertEquals(DependencyUsage.USED, status())
    }

    @Test
    fun `a baseline method is unreached when no instance hit it`() {
        manifest("i-1", recording = false, probes = listOf(method(1, 0, "demo.C", "m")))
        manifest("i-2")
        baseline("i-2", listOf(DeclaredClass("demo.C", listOf(DeclaredMethod("m", "()V", referencedClasses = libReference)))))

        assertEquals(DependencyUsage.UNREACHED, status())
        assertFalse(collector.dependency("org.example", "lib").sites.single().neverLoaded)
    }

    @Test
    fun `an incomplete scan contributes no reference, so no absent reference and no site`() {
        manifest("i-1")
        val missingAndLib = listOf("missing.Y", "org.example.Lib")
        val declared = DeclaredClass("demo.C", listOf(DeclaredMethod("m", "()V", referencedClasses = missingAndLib)))
        val externals = listOf(ExternalClass("missing.Y", null, absent = true), ExternalClass("org.example.Lib", 0))
        baseline("i-1", listOf(declared), chunkCount = 2, externals = externals)

        assertEquals(emptyList(), collector.absentReferences())
        assertEquals(emptyList(), collector.dependency("org.example", "lib").sites)

        baseline("i-1", emptyList(), chunkIndex = 1, chunkCount = 2, externals = externals)

        assertEquals(listOf("missing.Y"), collector.absentReferences().map { it.className })
        assertEquals(1, collector.dependency("org.example", "lib").sites.size)
    }

    @Test
    fun `an instance with a complete scan and a later incomplete one has a complete baseline`() {
        manifest("i-1")
        baseline("i-1", listOf(DeclaredClass("demo.C", listOf(DeclaredMethod("m", "()V", referencedClasses = libReference)))))
        baseline("i-1", emptyList(), scannedAt = 2000L, chunkCount = 2)

        assertEquals(listOf("org.example:lib"), collector.unreachedDependencies().map { it.identityKey })
    }

    @Test
    fun `a resent chunk changes nothing`() {
        baseline("i-1", listOf(DeclaredClass("demo.First", listOf(DeclaredMethod("run", "()V")))), chunkCount = 2)
        baseline("i-1", listOf(DeclaredClass("demo.Impostor", listOf(DeclaredMethod("run", "()V")))), chunkCount = 2)
        baseline("i-1", listOf(DeclaredClass("demo.Second", listOf(DeclaredMethod("run", "()V")))), chunkIndex = 1, chunkCount = 2)

        assertEquals(listOf("demo.First", "demo.Second"), collector.neverLoaded())
    }

    private fun callsTo(
        className: String,
        methodName: String,
    ) = listOf(CallEdge(className, methodName, "()V", false))

    private fun declaringCaller(callee: List<CallEdge>) = listOf(DeclaredClass("demo.C", listOf(DeclaredMethod("m", "()V", calls = callee))))

    private fun graphManifest(instanceId: String) =
        manifest(
            instanceId,
            recording = false,
            probes =
                listOf(
                    method(1, 0, "demo.C", "m"),
                    method(2, 1, "demo.D", "x"),
                    method(3, 2, "demo.E", "y"),
                ),
        )

    private fun assertBothCalleesReachedFromHit() {
        val clusters = collector.unreachedClusters()
        assertEquals(listOf("demo.D", "demo.E"), clusters.map { it.root.className }.sorted())
        assertTrue(clusters.all { it.rootKind == RootKind.REACHED_FROM_HIT }, clusters.toString())
    }

    @Test
    fun `baselines of two instances union their call edges, whichever arrives first`() {
        graphManifest("i-1")
        hit("i-1", 1, 0)
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")))
        baseline("i-2", declaringCaller(callsTo("demo.E", "y")))

        assertBothCalleesReachedFromHit()
        assertEquals(setOf("demo.D", "demo.E"), collector.callEdges("demo.C", "m").map { it.className }.toSet())
    }

    @Test
    fun `a never-loaded method two instances declare holds both their callees in one cluster`() {
        manifest("i-1", recording = false, probes = listOf(method(2, 0, "demo.D", "x"), method(3, 1, "demo.E", "y")))
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")))
        baseline("i-2", declaringCaller(callsTo("demo.E", "y")))

        val cluster = collector.unreachedClusters().single { it.root.className == "demo.C" }
        val classes = cluster.methods.map { it.className }.toSet()
        assertTrue("demo.D" in classes && "demo.E" in classes, cluster.toString())
    }

    @Test
    fun `a declared method of a skipped class blocks the callees of every instance's copy`() {
        manifest(
            "i-1",
            recording = false,
            probes = listOf(method(2, 0, "demo.D", "x"), method(3, 1, "demo.E", "y")),
            skipped = listOf(SkippedClass("demo.C", "too large", 1L)),
        )
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")))
        baseline("i-2", declaringCaller(callsTo("demo.E", "y")))

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { it.root.className == "demo.D" || it.root.className == "demo.E" }, clusters.toString())
    }

    @Test
    fun `the union of call edges does not depend on arrival order`() {
        graphManifest("i-1")
        hit("i-1", 1, 0)
        baseline("i-2", declaringCaller(callsTo("demo.E", "y")))
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")))

        assertBothCalleesReachedFromHit()
    }

    @Test
    fun `a later complete scan from an instance replaces its earlier one`() {
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")), scannedAt = 1000L)
        baseline("i-1", declaringCaller(callsTo("demo.E", "y")), scannedAt = 2000L)

        assertEquals(listOf("demo.E"), collector.callEdges("demo.C", "m").map { it.className })
    }

    @Test
    fun `an earlier scan that arrives late does not replace the latest one`() {
        baseline("i-1", declaringCaller(callsTo("demo.E", "y")), scannedAt = 2000L)
        baseline("i-1", declaringCaller(callsTo("demo.D", "x")), scannedAt = 1000L)

        assertEquals(listOf("demo.E"), collector.callEdges("demo.C", "m").map { it.className })
    }

    @Test
    fun `a class the latest scan of an instance no longer declares is not never loaded`() {
        baseline("i-1", listOf(DeclaredClass("demo.Old", listOf(DeclaredMethod("run", "()V")))), scannedAt = 1000L)
        baseline("i-1", listOf(DeclaredClass("demo.New", listOf(DeclaredMethod("run", "()V")))), scannedAt = 2000L)

        assertEquals(listOf("demo.New"), collector.neverLoaded())
    }
}
