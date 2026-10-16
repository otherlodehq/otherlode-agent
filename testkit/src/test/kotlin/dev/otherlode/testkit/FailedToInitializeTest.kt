package dev.otherlode.testkit

import dev.otherlode.export.CallEdge
import dev.otherlode.export.ClassFailedToInitialize
import dev.otherlode.export.ClassLocation
import dev.otherlode.export.ClassReferences
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
import dev.otherlode.export.FailedClass
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.InitializationFailure
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailedToInitializeTest {
    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun resource(instance: String) = ResourceAttributes("svc", null, instance, null, "run-1")

    private fun probe(
        classId: Int,
        index: Int,
        className: String,
        methodName: String,
        kind: ProbeKind = ProbeKind.METHOD,
        referenced: List<String> = emptyList(),
    ) = ProbeLocation(classId, index, kind, className, methodName, "()V", 1, null, referencedClasses = referenced)

    private fun initializerProbes(
        classId: Int,
        className: String,
    ) = listOf(
        probe(classId, 0, className, "<clinit>"),
        probe(classId, 1, className, "<clinit>", ProbeKind.INITIALIZER_RETURNED),
        probe(classId, 2, className, "<clinit>", ProbeKind.INITIALIZER_THREW),
    )

    private fun named(
        className: String,
        reason: InitializationFailure = InitializationFailure.STATIC_INITIALIZER_THREW,
    ) = ClassFailedToInitialize(className, reason, 1L)

    private fun manifest(
        instance: String,
        probes: List<ProbeLocation>,
        failed: List<ClassFailedToInitialize> = emptyList(),
        locations: List<ClassLocation> = emptyList(),
        watched: Boolean = true,
        unplaced: Long = 0L,
        failedToLoad: List<FailedClass> = emptyList(),
        dependencies: List<DependencyLocation> = emptyList(),
        externalClasses: List<ExternalClass> = emptyList(),
        endpoints: List<EndpointLocation> = emptyList(),
        classReferences: List<ClassReferences> = emptyList(),
    ) {
        exporter.exportManifest(
            watchedManifest(
                resource(instance),
                probes,
                classLocations = locations,
                failedClasses = failedToLoad,
                classesFailedToInitialize = failed,
                verifierRejectionsWatched = watched,
                unplacedVerificationFailures = unplaced,
                dependencies = dependencies,
                externalClasses = externalClasses,
                endpoints = endpoints,
                classReferences = classReferences,
                referencesRecorded = true,
                dependenciesListed = true,
            ),
        )
    }

    private fun hits(
        instance: String,
        vararg hit: Triple<Int, Int, ProbeKind>,
    ) {
        exporter.exportDeltaBatch(
            DeltaBatch(
                resource(instance),
                hit.map { (classId, index, kind) ->
                    ProbeDelta(classId, index, kind, 1L, 1L)
                },
            ),
        )
    }

    private fun method(
        classId: Int,
        index: Int,
    ) = Triple(classId, index, ProbeKind.METHOD)

    private fun lookup(
        classId: Int,
        superClassName: String? = null,
        vararg interfaces: String,
    ) = ClassLocation(classId, superClassName, interfaces.toList())

    private fun threwScenario(instance: String = "i-1") {
        manifest(
            instance,
            initializerProbes(1, "com.acme.Broken") +
                probe(1, 3, "com.acme.Broken", "run") +
                listOf(probe(2, 0, "com.acme.Sub", "<init>"), probe(2, 1, "com.acme.Sub", "sub")) +
                probe(3, 0, "com.acme.Plain", "unused") +
                listOf(probe(4, 0, "com.acme.Idle", "<init>"), probe(4, 1, "com.acme.Idle", "work")) +
                probe(5, 0, "com.acme.Healthy", "go"),
            failed = listOf(named("com.acme.Broken")),
            locations = listOf(lookup(1), lookup(2, "com.acme.Broken"), lookup(3), lookup(4), lookup(5)),
        )
        hits(instance, method(1, 0), Triple(1, 2, ProbeKind.INITIALIZER_THREW), method(5, 0))
    }

    @Test
    fun `failedToInitialize lists a class whose initialiser threw`() {
        threwScenario()

        assertEquals(listOf("com.acme.Broken", "com.acme.Sub"), collector.failedToInitialize())
    }

    @Test
    fun `failedToInitialize lists a class the verifier rejected`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Rejected") + probe(1, 3, "com.acme.Rejected", "run"),
            failed = listOf(named("com.acme.Rejected", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1)),
        )

        assertEquals(listOf("com.acme.Rejected"), collector.failedToInitialize())
    }

    @Test
    fun `failedToInitialize carries a failure to a confirmed subtype through a superclass, an interface and a chain`() {
        manifest(
            "i-1",
            listOf(
                probe(1, 0, "com.acme.Base", "m"),
                probe(2, 0, "com.acme.ViaSuper", "m"),
                probe(3, 0, "com.acme.ViaInterface", "m"),
                probe(4, 0, "com.acme.Deep", "m"),
                probe(5, 0, "com.acme.Unrelated", "m"),
            ),
            failed = listOf(named("com.acme.Base", InitializationFailure.VERIFIER_REJECTED)),
            locations =
                listOf(
                    lookup(1, "java.lang.Object"),
                    lookup(2, "com.acme.Base"),
                    lookup(3, "java.lang.Object", "com.acme.Base"),
                    lookup(4, "com.acme.ViaSuper"),
                    lookup(5, "java.lang.Object"),
                ),
        )

        assertEquals(
            listOf("com.acme.Base", "com.acme.Deep", "com.acme.ViaInterface", "com.acme.ViaSuper"),
            collector.failedToInitialize(),
        )
    }

    @Test
    fun `a thrown initialiser carries through the superclass, and through an interface only when it declares a default method`() {
        manifest(
            "i-1",
            listOf(
                probe(1, 0, "com.acme.ThrewBase", "m"),
                probe(2, 0, "com.acme.ThrewDefault", "dflt"),
                ProbeLocation(3, 0, ProbeKind.METHOD, "com.acme.ThrewStatic", "util", "()V", 1, null, static = true),
                probe(4, 0, "com.acme.ExtendsBase", "m"),
                probe(5, 0, "com.acme.ImplementsDefault", "m"),
                probe(6, 0, "com.acme.ImplementsStatic", "m"),
            ),
            failed =
                listOf(
                    named("com.acme.ThrewBase"),
                    named("com.acme.ThrewDefault"),
                    named("com.acme.ThrewStatic"),
                ),
            locations =
                listOf(
                    lookup(1, "java.lang.Object"),
                    lookup(2, "java.lang.Object"),
                    lookup(3, "java.lang.Object"),
                    lookup(4, "com.acme.ThrewBase"),
                    lookup(5, "java.lang.Object", "com.acme.ThrewDefault"),
                    lookup(6, "java.lang.Object", "com.acme.ThrewStatic"),
                ),
        )

        val failed = collector.failedToInitialize()

        assertTrue("com.acme.ExtendsBase" in failed, failed.toString())
        assertTrue("com.acme.ImplementsDefault" in failed, failed.toString())
        assertTrue("com.acme.ImplementsStatic" !in failed, failed.toString())
    }

    @Test
    fun `neverCalled leaves out an endpoint whose handler class failed to initialise`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.PromoResource", "promo"), probe(2, 0, "com.acme.OkResource", "ok")),
            failed = listOf(named("com.acme.PromoResource", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1), lookup(2)),
            endpoints =
                listOf(
                    EndpointLocation(1, "GET", "/promo", "/promo", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.PromoResource"),
                    EndpointLocation(2, "GET", "/ok", "/ok", "jaxrs", EndpointDiscoverySource.REGISTRATION, "com.acme.OkResource"),
                ),
        )

        assertEquals(listOf("/ok"), collector.neverCalled().map { it.routeTemplate })
    }

    @Test
    fun `a run stays unwatched once any of its manifests said so`() {
        manifest("i-1", listOf(probe(1, 0, "com.acme.Plain", "unused")), locations = listOf(lookup(1)), watched = false)
        manifest("i-1", emptyList(), watched = true)

        assertFailsWith<IllegalStateException> { collector.neverHit() }
    }

    @Test
    fun `a class-level reference from a class that failed to initialise is not live`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Broken", "run")),
            failed = listOf(named("com.acme.Broken", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1)),
            dependencies = listOf(library(0, "lib")),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
            classReferences = listOf(ClassReferences(1, listOf("org.example.Lib"))),
        )
        exporter.exportDeltaBatch(DeltaBatch(resource("i-1"), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L))))

        assertEquals(listOf(DependencyUsage.FAILED_TO_INITIALIZE), collector.failedToInitializeDependencies().map { it.status })
    }

    @Test
    fun `a method of a class that failed to initialise is not a member of a caller's cluster`() {
        manifest(
            "i-1",
            listOf(
                ProbeLocation(
                    1,
                    0,
                    ProbeKind.METHOD,
                    "com.acme.Root",
                    "go",
                    "()V",
                    1,
                    null,
                    calls = listOf(CallEdge("com.acme.Broken", "help", "()V", false)),
                ),
                probe(2, 0, "com.acme.Broken", "help"),
            ),
            failed = listOf(named("com.acme.Broken", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1), lookup(2)),
        )

        val members = collector.unreachedClusters().flatMap { listOf(it.root) + it.methods }
        assertTrue(members.any { it.className == "com.acme.Root" }, members.toString())
        assertTrue(members.none { it.className == "com.acme.Broken" }, members.toString())
    }

    @Test
    fun `an interface's own static initialiser is not a default method, so a failure in it does not carry to implementers`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.ThrewOnlyInit") + probe(2, 0, "com.acme.Implementer", "m"),
            failed = listOf(named("com.acme.ThrewOnlyInit")),
            locations = listOf(lookup(1, "java.lang.Object"), lookup(2, "java.lang.Object", "com.acme.ThrewOnlyInit")),
        )

        assertTrue("com.acme.Implementer" !in collector.failedToInitialize())
    }

    @Test
    fun `a thrown initialiser in an interface with a default method carries through an interface that extends it`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Base") + probe(1, 3, "com.acme.Base", "dflt") +
                probe(2, 0, "com.acme.Middle", "other") + probe(3, 0, "com.acme.Impl", "m"),
            failed = listOf(named("com.acme.Base")),
            locations =
                listOf(
                    lookup(1, "java.lang.Object"),
                    lookup(2, "java.lang.Object", "com.acme.Base"),
                    lookup(3, "java.lang.Object", "com.acme.Middle"),
                ),
        )

        assertTrue("com.acme.Impl" in collector.failedToInitialize(), collector.failedToInitialize().toString())
    }

    @Test
    fun `failedToInitialize carries a failure from a name no instance confirmed, and does not list that name`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Child", "m")),
            failed = listOf(named("lib.Outside", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1, "lib.Outside")),
        )

        assertEquals(listOf("com.acme.Child"), collector.failedToInitialize())
    }

    @Test
    fun `failedToInitialize leaves out a class another instance initialised`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Mixed", "m"), probe(2, 0, "com.acme.Child", "m")),
            failed = listOf(named("com.acme.Mixed")),
            locations = listOf(lookup(1), lookup(2, "com.acme.Mixed")),
        )
        manifest("i-2", listOf(probe(1, 0, "com.acme.Mixed", "m")), locations = listOf(lookup(1)))
        assertEquals(listOf("com.acme.Child", "com.acme.Mixed"), collector.failedToInitialize())

        hits("i-2", method(1, 0))

        assertEquals(listOf("com.acme.Child"), collector.failedToInitialize())
    }

    @Test
    fun `a class whose initialiser threw after calling a static helper is failed to initialise, not initialised`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Broken") + probe(1, 3, "com.acme.Broken", "helper"),
            failed = listOf(named("com.acme.Broken")),
            locations = listOf(lookup(1)),
        )
        hits("i-1", method(1, 0), method(1, 3), Triple(1, 2, ProbeKind.INITIALIZER_THREW))

        assertEquals(listOf("com.acme.Broken"), collector.failedToInitialize())
    }

    @Test
    fun `a class with no initialiser slots is initialised by any hit on it`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Plain", "m")),
            failed = listOf(named("com.acme.Plain", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1)),
        )
        assertEquals(listOf("com.acme.Plain"), collector.failedToInitialize())

        hits("i-1", method(1, 0))

        assertEquals(emptyList(), collector.failedToInitialize())
    }

    @Test
    fun `an initialiser that returned in another instance counts as the class initialised there`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Mixed"),
            failed = listOf(named("com.acme.Mixed")),
            locations = listOf(lookup(1)),
        )
        manifest("i-2", initializerProbes(1, "com.acme.Mixed"), locations = listOf(lookup(1)))
        hits("i-2", Triple(1, 1, ProbeKind.INITIALIZER_RETURNED))

        assertEquals(emptyList(), collector.failedToInitialize())
    }

    @Test
    fun `a subtype carried by supertypes in one instance is not carried by another instance's supertypes`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Base", "m"), probe(2, 0, "com.acme.Child", "m")),
            failed = listOf(named("com.acme.Base")),
            locations = listOf(lookup(1), lookup(2, "java.lang.Object")),
        )
        manifest(
            "i-2",
            listOf(probe(1, 0, "com.acme.Child", "m")),
            locations = listOf(lookup(1, "com.acme.Base")),
        )

        assertEquals(listOf("com.acme.Base"), collector.failedToInitialize())
    }

    @Test
    fun `neverHit, the class findings and the clusters leave out a class that failed to initialise and its subtypes`() {
        threwScenario()

        assertEquals(listOf("com.acme.Plain"), collector.neverHit().map { it.className }.distinct())
        assertEquals(emptyList(), collector.neverInitialized().map { it.className })
        assertEquals(listOf("com.acme.Idle"), collector.neverInstantiated().map { it.className })
        val clusterClasses =
            collector
                .unreachedClusters()
                .flatMap { cluster ->
                    listOf(cluster.root) + cluster.methods
                }.map { it.className }
        assertEquals(listOf("com.acme.Plain"), clusterClasses.distinct())
    }

    @Test
    fun `a class the verifier rejected is not never initialised, and its methods are not never hit`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Rejected") + probe(1, 3, "com.acme.Rejected", "run") + probe(2, 0, "com.acme.Plain", "unused"),
            failed = listOf(named("com.acme.Rejected", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1), lookup(2)),
        )

        assertEquals(emptyList(), collector.neverInitialized())
        assertEquals(listOf("com.acme.Plain"), collector.neverHit().map { it.className })
        assertEquals(emptyList(), collector.neverHitRoutineOutcomes())
        assertEquals(emptyList(), collector.neverHitUnreadShapes())
    }

    @Test
    fun `a class another instance initialised is judged from its zero counts`() {
        manifest(
            "i-1",
            initializerProbes(1, "com.acme.Mixed") + probe(1, 3, "com.acme.Mixed", "run"),
            failed = listOf(named("com.acme.Mixed", InitializationFailure.VERIFIER_REJECTED)),
            locations = listOf(lookup(1)),
        )
        manifest(
            "i-2",
            initializerProbes(1, "com.acme.Mixed") + probe(1, 3, "com.acme.Mixed", "run"),
            locations = listOf(lookup(1)),
        )
        hits("i-2", method(1, 0), Triple(1, 1, ProbeKind.INITIALIZER_RETURNED))

        assertEquals(emptyList(), collector.failedToInitialize())
        assertEquals(listOf("run"), collector.neverHit().map { it.methodName })
    }

    private fun stillRunning(
        instance: String,
        returned: Boolean = false,
        withSlots: Boolean = true,
    ) {
        val probes =
            (if (withSlots) initializerProbes(1, "com.acme.Slow") else listOf(probe(1, 0, "com.acme.Slow", "<clinit>"))) +
                listOf(probe(1, 3, "com.acme.Slow", "<init>"), probe(1, 4, "com.acme.Slow", "work"))
        manifest(instance, probes, locations = listOf(lookup(1)))
        hits(instance, method(1, 0), *(if (returned) arrayOf(Triple(1, 1, ProbeKind.INITIALIZER_RETURNED)) else emptyArray()))
    }

    @Test
    fun `an initialiser still running gives no class finding from that instance`() {
        stillRunning("i-1")

        assertEquals(emptyList(), collector.neverInstantiated())
        assertEquals(emptyList(), collector.neverInitialized())
        assertEquals(emptyList(), collector.failedToInitialize())
    }

    @Test
    fun `an initialiser still running withholds the class's never-hit rows and cluster members too`() {
        stillRunning("i-1")

        assertTrue(collector.neverHit().none { it.className == "com.acme.Slow" }, collector.neverHit().toString())
        assertTrue(
            collector.unreachedClusters().flatMap { listOf(it.root) + it.methods }.none { it.className == "com.acme.Slow" },
        )
    }

    @Test
    fun `an initialiser entered through one loader stays running though another loader's copy never entered it`() {
        val probes =
            initializerProbes(
                1,
                "com.acme.Twice",
            ) + listOf(probe(1, 3, "com.acme.Twice", "<init>"), probe(1, 4, "com.acme.Twice", "work")) +
                initializerProbes(2, "com.acme.Twice") +
                listOf(probe(2, 3, "com.acme.Twice", "<init>"), probe(2, 4, "com.acme.Twice", "work"))
        manifest("i-1", probes, locations = listOf(lookup(1), lookup(2)))
        hits("i-1", method(1, 0))

        assertEquals(emptyList(), collector.neverInstantiated())
    }

    @Test
    fun `an initialiser that returned still gives the class finding`() {
        stillRunning("i-1", returned = true)

        assertEquals(listOf("com.acme.Slow"), collector.neverInstantiated().map { it.className })
    }

    @Test
    fun `a class without initialiser slots is judged by its own zero counts`() {
        stillRunning("i-1", withSlots = false)

        assertEquals(listOf("com.acme.Slow"), collector.neverInstantiated().map { it.className })
    }

    @Test
    fun `another instance whose initialiser returned still gives the finding while one is still running`() {
        stillRunning("i-1")
        stillRunning("i-2", returned = true)

        assertEquals(listOf("com.acme.Slow"), collector.neverInstantiated().map { it.className })
    }

    private fun library(
        id: Int,
        artifact: String,
    ) = DependencyLocation(
        dependencyId = id,
        identities = listOf(DependencyIdentity("org.example", artifact, "1.0")),
        identitySource = DependencyIdentitySource.POM_PROPERTIES,
        location = "/libs/$artifact.jar",
        discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
        classCount = 10,
    )

    private fun libraryReferencedBy(
        instance: String,
        className: String,
        failed: List<ClassFailedToInitialize>,
        failedToLoad: List<FailedClass> = emptyList(),
    ) {
        manifest(
            instance,
            listOf(probe(1, 0, className, "run", referenced = listOf("org.example.Lib"))),
            failed = failed,
            locations = listOf(lookup(1)),
            failedToLoad = failedToLoad,
            dependencies = listOf(library(0, "lib")),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
        )
        exporter.exportDeltaBatch(DeltaBatch(resource(instance), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L))))
    }

    @Test
    fun `a dependency referenced only from a class that failed to initialise reads as failed to initialise`() {
        libraryReferencedBy("i-1", "com.acme.Broken", listOf(named("com.acme.Broken")))

        val status = collector.failedToInitializeDependencies().single()

        assertEquals("org.example:lib", status.identityKey)
        assertEquals(DependencyUsage.FAILED_TO_INITIALIZE, status.status)
        assertEquals(
            listOf(DependencyReferenceSite("com.acme.Broken", "run", neverLoaded = false, failedToInitialize = true)),
            status.sites,
        )
        assertEquals("com.acme.Broken#run (failed to initialise)", status.sites.single().toString())
        assertEquals(emptyList(), collector.failedToLoadDependencies())
        assertEquals(emptyList(), collector.unreachedDependencies())
        assertEquals(DependencyUsage.FAILED_TO_INITIALIZE, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `a dependency referenced from a class another instance initialised is not failed to initialise`() {
        libraryReferencedBy("i-1", "com.acme.Broken", listOf(named("com.acme.Broken")))
        libraryReferencedBy("i-2", "com.acme.Broken", emptyList())
        hits("i-2", method(1, 0))

        assertEquals(emptyList(), collector.failedToInitializeDependencies())
        assertEquals(DependencyUsage.USED, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `a dependency referenced from a class that failed to initialise and one that failed to load reads as failed to load`() {
        libraryReferencedBy("i-1", "com.acme.Broken", listOf(named("com.acme.Broken")), listOf(FailedClass("com.acme.Gone", 1L)))
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = resource("i-1"),
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            "com.acme.Gone",
                            listOf(DeclaredMethod("apply", "()V", referencedClasses = listOf("org.example.Lib"))),
                        ),
                    ),
                scannedAt = 1000L,
            ),
        )

        assertEquals(DependencyUsage.FAILED_TO_LOAD, collector.dependency("org.example", "lib").status)
        assertEquals(emptyList(), collector.failedToInitializeDependencies())
    }

    private fun refusingQueries(): Map<String, () -> Any> =
        mapOf(
            "neverHit" to { collector.neverHit() },
            "neverCalled" to { collector.neverCalled() },
            "alwaysSupplied" to { collector.alwaysSupplied() },
            "neverHitRoutineOutcomes" to { collector.neverHitRoutineOutcomes() },
            "neverHitUnreadShapes" to { collector.neverHitUnreadShapes() },
            "neverInitialized" to { collector.neverInitialized() },
            "neverInstantiated" to { collector.neverInstantiated() },
            "unreachedClusters" to { collector.unreachedClusters() },
            "unreachedDependencies" to { collector.unreachedDependencies() },
            "failedToLoadDependencies" to { collector.failedToLoadDependencies() },
            "failedToInitializeDependencies" to { collector.failedToInitializeDependencies() },
            "dependency" to { collector.dependency("org.example", "lib") },
        )

    private fun answeringQueries(): Map<String, () -> Any> =
        mapOf(
            "wasHit" to { collector.wasHit("com.acme.Plain", "unused") },
            "hitCount" to { collector.hitCount("com.acme.Plain", "unused") },
            "failedToLoad" to { collector.failedToLoad() },
            "failedToInitialize" to { collector.failedToInitialize() },
            "neverSupplied" to { collector.neverSupplied() },
            "unloadedDependencies" to { collector.unloadedDependencies() },
        )

    private fun referencingManifest(
        instance: String,
        watched: Boolean = true,
        unplaced: Long = 0L,
    ) = manifest(
        instance,
        listOf(probe(1, 0, "com.acme.Plain", "unused", referenced = listOf("org.example.Lib"))),
        locations = listOf(lookup(1)),
        watched = watched,
        unplaced = unplaced,
        dependencies = listOf(library(0, "lib")),
        externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
    ).also {
        exporter.exportDeltaBatch(DeltaBatch(resource(instance), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L))))
    }

    @Test
    fun `every query that rests on zero counts refuses while an instance is unwatched, and names it`() {
        referencingManifest("i-1")
        referencingManifest("i-2", watched = false)

        for ((name, query) in refusingQueries()) {
            val refusal = assertFailsWith<IllegalStateException>(name) { query() }
            val message = refusal.message.orEmpty()
            assertTrue(message.contains("i-2: the agent's hook on VerifyError was not installed"), "$name: $message")
            assertFalse(message.contains("i-1"), "$name: $message")
        }
    }

    @Test
    fun `every query that rests on zero counts refuses while an instance has an unplaced verification failure, and names it`() {
        referencingManifest("i-1")
        referencingManifest("i-2", unplaced = 3L)

        for ((name, query) in refusingQueries()) {
            val refusal = assertFailsWith<IllegalStateException>(name) { query() }
            val message = refusal.message.orEmpty()
            assertTrue(message.contains("i-2: 3 VerifyError messages in no read format"), "$name: $message")
            assertFalse(message.contains("i-1"), "$name: $message")
        }
    }

    @Test
    fun `the refusal names every instance and each reason it has`() {
        referencingManifest("i-1", watched = false, unplaced = 2L)
        referencingManifest("i-2", unplaced = 1L)

        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()

        assertTrue(
            message.contains("i-1: the agent's hook on VerifyError was not installed; 2 VerifyError messages in no read format"),
            message,
        )
        assertTrue(message.contains("i-2: 1 VerifyError message in no read format"), message)
    }

    @Test
    fun `a query that does not rest on zero counts still answers while an instance is unwatched or has an unplaced failure`() {
        referencingManifest("i-1", watched = false)
        referencingManifest("i-2", unplaced = 3L)

        for ((name, query) in answeringQueries()) {
            runCatching { query() }.onFailure { throw AssertionError("$name refused: ${it.message}", it) }
        }
    }

    @Test
    fun `a dependency whose status does not rest on zero counts answers while an instance is unwatched`() {
        libraryReferencedBy("i-1", "com.acme.Broken", emptyList())
        hits("i-1", method(1, 0))
        referencingManifest("i-2", watched = false)

        assertEquals(DependencyUsage.USED, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `a dependency with no reference at all answers no live reference while an instance is unwatched`() {
        manifest(
            "i-1",
            listOf(probe(1, 0, "com.acme.Plain", "unused")),
            locations = listOf(lookup(1)),
            watched = false,
            dependencies = listOf(library(0, "lib")),
        )
        exporter.exportDeltaBatch(DeltaBatch(resource("i-1"), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L))))

        assertEquals(DependencyUsage.NO_LIVE_REFERENCE, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `a watched instance with no unplaced failure lets the zero-count queries answer`() {
        referencingManifest("i-1")

        assertEquals(listOf("com.acme.Plain"), collector.neverHit().map { it.className })
    }
}
