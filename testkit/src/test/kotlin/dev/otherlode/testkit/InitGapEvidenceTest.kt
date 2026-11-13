package dev.otherlode.testkit

import dev.otherlode.export.CallEdge
import dev.otherlode.export.ClassFailedToInitialize
import dev.otherlode.export.ClassLocation
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.DependencyDelta
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.DependencyIdentity
import dev.otherlode.export.DependencyIdentitySource
import dev.otherlode.export.DependencyLocation
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.ExternalClass
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.SkippedClass
import dev.otherlode.export.StaticBaseline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InitGapEvidenceTest {
    private enum class Gap { FAILED, RUNNING, NONE }

    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun resource(instance: String) = ResourceAttributes("svc", null, instance, null, "run-$instance")

    private fun probe(
        classId: Int,
        index: Int,
        className: String,
        methodName: String,
        kind: ProbeKind = ProbeKind.METHOD,
        calls: List<CallEdge> = emptyList(),
        generatedBy: GeneratedBy = GeneratedBy.NONE,
        parameterIndex: Int? = null,
        targetClassName: String? = null,
        branchIndex: Int? = null,
        referenced: List<String> = emptyList(),
    ) = ProbeLocation(
        classId,
        index,
        kind,
        className,
        methodName,
        "()V",
        1,
        branchIndex,
        parameterIndex = parameterIndex,
        parameterName = parameterIndex?.let { "p$it" },
        targetClassName = targetClassName,
        calls = calls,
        generatedBy = generatedBy,
        siteIndex = if (kind == ProbeKind.BRANCH) 0 else null,
        referencedClasses = referenced,
    )

    private fun call(
        className: String,
        methodName: String,
        guard: Int? = null,
    ) = CallEdge(className, methodName, "()V", false, guard = guard)

    private fun initializer(
        classId: Int,
        className: String,
        calls: List<CallEdge> = emptyList(),
    ) = listOf(
        probe(classId, 0, className, "<clinit>", calls = calls),
        probe(classId, 1, className, "<clinit>", ProbeKind.INITIALIZER_RETURNED),
        probe(classId, 2, className, "<clinit>", ProbeKind.INITIALIZER_THREW),
    )

    private fun gapHits(
        gap: Gap,
        classId: Int,
    ) = if (gap == Gap.NONE) listOf(classId to 0, classId to 1) else listOf(classId to 0)

    private fun failedNames(
        gap: Gap,
        className: String,
    ) = if (gap == Gap.FAILED) listOf(className) else emptyList()

    private fun send(
        probes: List<ProbeLocation>,
        hits: List<Pair<Int, Int>>,
        failed: List<String> = emptyList(),
        skipped: List<String> = emptyList(),
        endpoints: List<EndpointLocation> = emptyList(),
        dependencies: List<DependencyLocation> = emptyList(),
        externalClasses: List<ExternalClass> = emptyList(),
        instance: String = "i-1",
    ) {
        exporter.exportManifest(
            watchedManifest(
                resource(instance),
                probes,
                skippedClasses = skipped.map { SkippedClass(it, "too large", 1L) },
                endpoints = endpoints,
                classLocations = probes.map { it.classId }.distinct().map { ClassLocation(it, null, emptyList()) },
                classesFailedToInitialize = failed.map { ClassFailedToInitialize(it, InitializationFailure.STATIC_INITIALIZER_THREW, 1L) },
                dependencies = dependencies,
                externalClasses = externalClasses,
                referencesRecorded = true,
                dependenciesListed = true,
            ),
        )
        val kinds = probes.associate { (it.classId to it.probeIndex) to it.kind }
        exporter.exportDeltaBatch(
            DeltaBatch(
                resource(instance),
                hits.map { (classId, index) -> ProbeDelta(classId, index, kinds.getValue(classId to index), 1L, 5L) },
                dependencyDeltas = if (dependencies.isEmpty()) emptyList() else listOf(DependencyDelta(0, 1L, 1L)),
            ),
        )
    }

    private fun mentions(
        cluster: UnreachedCluster,
        className: String,
    ) = cluster.root.className == className ||
        cluster.methods.any { it.className == className } ||
        cluster.wholeClasses.any { it.className == className }

    private fun omissionScenario(
        gap: Gap,
        withHealthyOmission: Boolean = false,
        omissionHits: Boolean = false,
    ) {
        val probes =
            initializer(1, "com.acme.Gap") +
                probe(1, 3, "com.acme.Gap", "helper", ProbeKind.OPTIONAL_ARGUMENT, parameterIndex = 0, targetClassName = "com.acme.C") +
                probe(2, 0, "com.acme.C", "helper") +
                (
                    if (withHealthyOmission) {
                        listOf(probe(3, 0, "com.acme.Ok", "helper", ProbeKind.OPTIONAL_ARGUMENT, parameterIndex = 0, targetClassName = "com.acme.C"))
                    } else {
                        emptyList()
                    }
                )
        send(
            probes,
            gapHits(gap, 1) + (2 to 0) + (if (omissionHits) listOf(1 to 3) else emptyList()),
            failed = failedNames(gap, "com.acme.Gap"),
        )
    }

    @Test
    fun `alwaysSupplied leaves out a parameter whose only omission probe is in a class that failed to initialise`() {
        omissionScenario(Gap.FAILED)

        assertEquals(emptyList(), collector.alwaysSupplied())
    }

    @Test
    fun `alwaysSupplied leaves out a parameter whose only omission probe is in a class whose initialiser is running`() {
        omissionScenario(Gap.RUNNING)

        assertEquals(emptyList(), collector.alwaysSupplied())
    }

    @Test
    fun `alwaysSupplied still lists a parameter whose omission probe is in an initialised class`() {
        omissionScenario(Gap.NONE)

        assertEquals(listOf("com.acme.C" to "helper"), collector.alwaysSupplied().map { it.className to it.methodName })
    }

    @Test
    fun `alwaysSupplied still lists a parameter another class's omission probe also covers`() {
        omissionScenario(Gap.FAILED, withHealthyOmission = true)

        assertEquals(listOf("com.acme.C" to "helper"), collector.alwaysSupplied().map { it.className to it.methodName })
    }

    @Test
    fun `neverSupplied rests on hits, so a class that failed to initialise does not withhold it`() {
        omissionScenario(Gap.FAILED, omissionHits = true)

        assertEquals(listOf("com.acme.C" to "helper"), collector.neverSupplied().map { it.className to it.methodName })
    }

    private fun endpointScenario(gap: Gap) {
        send(
            initializer(1, "com.acme.PromoResource") + probe(1, 3, "com.acme.PromoResource", "promo") +
                probe(2, 0, "com.acme.OkResource", "ok"),
            gapHits(gap, 1),
            failed = failedNames(gap, "com.acme.PromoResource"),
            endpoints =
                listOf(
                    EndpointLocation(1, "GET", "/promo", "/promo", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.PromoResource"),
                    EndpointLocation(2, "GET", "/ok", "/ok", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.OkResource"),
                ),
        )
    }

    @Test
    fun `neverCalled leaves out an endpoint whose handler class's initialiser is running`() {
        endpointScenario(Gap.RUNNING)

        assertEquals(listOf("/ok"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `neverCalled leaves out an endpoint whose every reporting instance failed its handler, though another instance initialised it`() {
        endpointScenario(Gap.FAILED)
        send(initializer(1, "com.acme.PromoResource"), gapHits(Gap.NONE, 1), instance = "i-2")

        assertEquals(listOf("/ok"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `neverCalled lists an endpoint an instance that initialised its handler also reports`() {
        endpointScenario(Gap.FAILED)
        send(
            initializer(1, "com.acme.PromoResource") + probe(1, 3, "com.acme.PromoResource", "promo"),
            gapHits(Gap.NONE, 1),
            endpoints =
                listOf(EndpointLocation(1, "GET", "/promo", "/promo", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.PromoResource")),
            instance = "i-2",
        )

        assertEquals(listOf("/ok", "/promo"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `neverCalled leaves out an endpoint whose handler failed overall, though another reporting instance only loaded it`() {
        endpointScenario(Gap.FAILED)
        send(
            initializer(1, "com.acme.PromoResource") + probe(1, 3, "com.acme.PromoResource", "promo"),
            emptyList(),
            endpoints =
                listOf(EndpointLocation(1, "GET", "/promo", "/promo", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.PromoResource")),
            instance = "i-2",
        )

        assertEquals(listOf("com.acme.PromoResource"), collector.failedToInitialize())
        assertEquals(listOf("/ok"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `neverCalled lists an endpoint whose reporting instance names its handler failed but sent no probe of it`() {
        send(
            listOf(probe(2, 0, "com.acme.OkResource", "ok")),
            emptyList(),
            failed = listOf("com.acme.PromoResource"),
            endpoints =
                listOf(EndpointLocation(1, "GET", "/promo", "/promo", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.PromoResource")),
        )

        assertEquals(listOf("/promo"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `neverCalled still lists an endpoint whose handler class initialised`() {
        endpointScenario(Gap.NONE)

        assertEquals(listOf("/ok", "/promo"), collector.neverCalled().map { it.routeTemplate })
    }

    private fun library() =
        DependencyLocation(
            dependencyId = 0,
            identities = listOf(DependencyIdentity("org.example", "lib", "1.0")),
            identitySource = DependencyIdentitySource.POM_PROPERTIES,
            location = "/libs/lib.jar",
            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 10,
        )

    private fun dependencyScenario(gap: Gap) {
        send(
            initializer(1, "com.acme.Slow") + probe(1, 3, "com.acme.Slow", "run", referenced = listOf("org.example.Lib")),
            gapHits(gap, 1),
            failed = failedNames(gap, "com.acme.Slow"),
            dependencies = listOf(library()),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
        )
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource("i-1"),
                listOf(DeclaredClass("com.acme.Slow", listOf(DeclaredMethod("run", "()V", referencedClasses = listOf("org.example.Lib"))))),
                scannedAt = 1000L,
            ),
        )
    }

    @Test
    fun `a dependency referenced only from an unhit method of a class whose initialiser is running has no evidence`() {
        dependencyScenario(Gap.RUNNING)

        assertEquals(DependencyUsage.WITHOUT_EVIDENCE, collector.dependency("org.example", "lib").status)
        assertEquals(emptyList(), collector.unreachedDependencies())
        assertEquals(emptyList(), collector.unreferencedDependencies())
    }

    @Test
    fun `a dependency also referenced from an unhit method of an initialised class still has no evidence`() {
        send(
            initializer(1, "com.acme.Slow") + probe(1, 3, "com.acme.Slow", "run", referenced = listOf("org.example.Lib")) +
                probe(2, 0, "com.acme.Ok", "idle", referenced = listOf("org.example.Lib")),
            gapHits(Gap.RUNNING, 1),
            dependencies = listOf(library()),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
        )
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource("i-1"),
                listOf(
                    DeclaredClass("com.acme.Slow", listOf(DeclaredMethod("run", "()V", referencedClasses = listOf("org.example.Lib")))),
                    DeclaredClass("com.acme.Ok", listOf(DeclaredMethod("idle", "()V", referencedClasses = listOf("org.example.Lib")))),
                ),
                scannedAt = 1000L,
            ),
        )

        assertEquals(DependencyUsage.WITHOUT_EVIDENCE, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `a dependency referenced only from an unhit method of an initialised class reads unreached`() {
        dependencyScenario(Gap.NONE)

        assertEquals(DependencyUsage.UNREACHED, collector.dependency("org.example", "lib").status)
        assertEquals(listOf("org.example:lib"), collector.unreachedDependencies().map { it.identityKey })
    }

    private fun hitMethodScenario(gap: Gap) {
        send(
            initializer(1, "com.acme.Gap") + probe(1, 3, "com.acme.Gap", "run", calls = listOf(call("com.acme.T", "helper"))) +
                probe(2, 0, "com.acme.T", "helper"),
            gapHits(gap, 1) + (1 to 3),
            failed = failedNames(gap, "com.acme.Gap"),
        )
    }

    @Test
    fun `a never-hit method called only from a hit method of a class that failed to initialise has no cluster`() {
        hitMethodScenario(Gap.FAILED)

        assertEquals(emptyList(), collector.unreachedClusters())
        assertTrue(collector.neverHit().any { it.className == "com.acme.T" })
    }

    @Test
    fun `a never-hit method called only from a hit method of a class whose initialiser is running is reached from hit`() {
        hitMethodScenario(Gap.RUNNING)

        val cluster = collector.unreachedClusters().single()
        assertEquals("com.acme.T" to "helper", cluster.root.className to cluster.root.methodName)
        assertEquals(RootKind.REACHED_FROM_HIT, cluster.rootKind)
    }

    @Test
    fun `a never-hit method called only from the hit static initialiser of a class that failed to initialise has no cluster`() {
        send(
            initializer(1, "com.acme.Gap", calls = listOf(call("com.acme.T", "helper"))) + probe(2, 0, "com.acme.T", "helper"),
            gapHits(Gap.FAILED, 1),
            failed = listOf("com.acme.Gap"),
        )

        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a never-hit method called from a hit method of an initialised class is still reached from hit`() {
        hitMethodScenario(Gap.NONE)

        val cluster = collector.unreachedClusters().single()
        assertEquals("com.acme.T" to "helper", cluster.root.className to cluster.root.methodName)
        assertEquals(RootKind.REACHED_FROM_HIT, cluster.rootKind)
        assertEquals(listOf("com.acme.Gap" to "run"), cluster.reachedFrom.map { it.className to it.methodName })
    }

    private fun outcomeScenario(gap: Gap) {
        send(
            initializer(1, "com.acme.Gap") +
                probe(1, 3, "com.acme.Gap", "handle", calls = listOf(call("com.acme.D", "helper", guard = 1))) +
                probe(1, 4, "com.acme.Gap", "handle", ProbeKind.BRANCH, branchIndex = 0) +
                probe(1, 5, "com.acme.Gap", "handle", ProbeKind.BRANCH, branchIndex = 1) +
                probe(2, 0, "com.acme.D", "helper"),
            gapHits(gap, 1) + listOf(1 to 3, 1 to 4),
            failed = failedNames(gap, "com.acme.Gap"),
        )
    }

    @Test
    fun `a call guarded by a never-hit outcome of a class that failed to initialise has no cluster`() {
        outcomeScenario(Gap.FAILED)

        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a call guarded by a never-hit outcome of a class whose initialiser is running has no cluster`() {
        outcomeScenario(Gap.RUNNING)

        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a call guarded by a never-hit outcome of an initialised class roots at the outcome`() {
        outcomeScenario(Gap.NONE)

        val clusters = collector.unreachedClusters()
        assertEquals(listOf(RootKind.UNTAKEN_OUTCOME), clusters.map { it.rootKind }, clusters.toString())
        assertTrue(clusters.any { mentions(it, "com.acme.D") }, clusters.toString())
    }

    private fun accessorScenario(gap: Gap) {
        send(
            initializer(1, "com.acme.Gap") +
                probe(1, 3, "com.acme.Gap", "access\$000", generatedBy = GeneratedBy.STATIC_FORWARDER, calls = listOf(call("com.acme.D", "helper"))) +
                probe(2, 0, "com.acme.D", "helper"),
            gapHits(gap, 1),
            failed = failedNames(gap, "com.acme.Gap"),
        )
    }

    @Test
    fun `a hit generated method of a class that failed to initialise is still a hit caller`() {
        send(
            initializer(1, "com.acme.Gap") +
                probe(1, 3, "com.acme.Gap", "access\$000", generatedBy = GeneratedBy.STATIC_FORWARDER, calls = listOf(call("com.acme.D", "helper"))) +
                probe(2, 0, "com.acme.D", "helper"),
            gapHits(Gap.FAILED, 1) + (1 to 3),
            failed = listOf("com.acme.Gap"),
        )

        val cluster = collector.unreachedClusters().single()
        assertEquals("com.acme.D" to "helper", cluster.root.className to cluster.root.methodName)
        assertEquals(RootKind.REACHED_FROM_HIT, cluster.rootKind)
    }

    @Test
    fun `a never-hit generated method of a class that failed to initialise blocks its callee`() {
        accessorScenario(Gap.FAILED)

        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a never-hit generated method of a class whose initialiser is running blocks its callee`() {
        accessorScenario(Gap.RUNNING)

        assertEquals(emptyList(), collector.unreachedClusters())
    }

    @Test
    fun `a never-hit generated method of a skipped class blocks its callee`() {
        send(
            listOf(
                probe(1, 0, "com.acme.X", "access\$000", generatedBy = GeneratedBy.STATIC_FORWARDER, calls = listOf(call("com.acme.D", "helper"))),
                probe(2, 0, "com.acme.D", "helper"),
                probe(3, 0, "com.acme.H", "go"),
            ),
            listOf(3 to 0),
        )
        send(emptyList(), emptyList(), skipped = listOf("com.acme.X"), instance = "i-2")

        val clusters = collector.unreachedClusters()
        assertTrue(clusters.none { mentions(it, "com.acme.D") }, clusters.toString())
    }

    @Test
    fun `a never-hit generated method of an initialised class leaves its callee uncalled`() {
        accessorScenario(Gap.NONE)

        val cluster = collector.unreachedClusters().single()
        assertEquals("com.acme.D" to "helper", cluster.root.className to cluster.root.methodName)
        assertEquals(RootKind.UNCALLED, cluster.rootKind)
    }
}
