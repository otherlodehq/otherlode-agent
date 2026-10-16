package com.example.demo.collector

import com.google.protobuf.MessageLite
import dev.otherlode.proto.CallEdge
import dev.otherlode.proto.ClassFailedToInitialize
import dev.otherlode.proto.ClassLocation
import dev.otherlode.proto.ClassReferences
import dev.otherlode.proto.DeltaBatch
import dev.otherlode.proto.DependencyDelta
import dev.otherlode.proto.DependencyDiscoverySource
import dev.otherlode.proto.DependencyIdentity
import dev.otherlode.proto.DependencyIdentitySource
import dev.otherlode.proto.DependencyLocation
import dev.otherlode.proto.EndpointDiscoverySource
import dev.otherlode.proto.EndpointLocation
import dev.otherlode.proto.ExternalClass
import dev.otherlode.proto.InitializationFailure
import dev.otherlode.proto.ProbeDelta
import dev.otherlode.proto.ProbeKind
import dev.otherlode.proto.ProbeLocation
import dev.otherlode.proto.ProbeManifest
import dev.otherlode.proto.ResourceAttributes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubCollectorFailedToInitialiseTest {
    private val server = startStubCollector(0)
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun resource(
        instance: String,
        runId: String,
    ): ResourceAttributes =
        ResourceAttributes
            .newBuilder()
            .setServiceName("svc")
            .setServiceInstanceId(instance)
            .setRunId(runId)
            .build()

    private fun post(
        path: String,
        payload: MessageLite,
    ) {
        val status =
            client
                .send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:${server.address.port}/v1/otherlode/$path"))
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray()))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
        assertEquals(200, status)
    }

    private fun probe(
        classId: Int,
        index: Int,
        className: String,
        method: String,
        kind: ProbeKind = ProbeKind.METHOD,
    ): ProbeLocation =
        ProbeLocation
            .newBuilder()
            .setClassId(classId)
            .setProbeIndex(index)
            .setKind(kind)
            .setClassName(className)
            .setMethodName(method)
            .setMethodDescriptor("()V")
            .build()

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
        reason: InitializationFailure,
    ): ClassFailedToInitialize =
        ClassFailedToInitialize
            .newBuilder()
            .setClassName(className)
            .setReason(reason)
            .setObservedAt(1L)
            .build()

    private fun location(
        classId: Int,
        superClassName: String = "java.lang.Object",
    ): ClassLocation =
        ClassLocation
            .newBuilder()
            .setClassId(classId)
            .setSuperClassName(superClassName)
            .build()

    private fun manifest(
        instance: String,
        runId: String,
        probes: List<ProbeLocation>,
        locations: List<ClassLocation> = emptyList(),
        failed: List<ClassFailedToInitialize> = emptyList(),
        watched: Boolean = true,
        unplaced: Long = 0L,
    ) = post(
        "manifest",
        ProbeManifest
            .newBuilder()
            .setResource(resource(instance, runId))
            .addAllProbes(probes)
            .addAllClassLocations(locations)
            .addAllClassesFailedToInitialize(failed)
            .setVerifierRejectionsWatched(watched)
            .setUnplacedVerificationFailures(unplaced)
            .build(),
    )

    private fun hit(
        instance: String,
        runId: String,
        classId: Int,
        index: Int,
        kind: ProbeKind = ProbeKind.METHOD,
    ) = post(
        "deltas",
        DeltaBatch
            .newBuilder()
            .setResource(resource(instance, runId))
            .addDeltas(
                ProbeDelta
                    .newBuilder()
                    .setClassId(classId)
                    .setProbeIndex(index)
                    .setKind(kind)
                    .setFirstSeenAt(1L)
                    .setHitsTotal(1L),
            ).build(),
    )

    private fun report(print: () -> Unit): List<String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            print()
        } finally {
            System.setOut(original)
        }
        return captured.toString().lines()
    }

    private fun neverHitReport() = report(::printNeverHitReport)

    private fun classFindingReport() = report(::printClassFindingReport)

    private fun failedToInitialiseReport() = report(::printFailedToInitialiseReport)

    private fun clusterReport() = report(::printUnreachedClusterReport)

    private fun endpointReport() = report(::printEndpointReport)

    private fun omissionReport() = report(::printOmissionReport)

    private fun dependencyReport() = report(::printDependencyReport)

    private fun endpoint(
        route: String,
        handler: String,
    ) = EndpointLocation
        .newBuilder()
        .setEndpointId(1)
        .setVerb("GET")
        .setRouteTemplate(route)
        .setVerbatimTemplate(route)
        .setFramework("jaxrs")
        .setDiscoverySource(EndpointDiscoverySource.REGISTRATION)
        .setHandlerClass(handler)
        .build()

    private fun outcomeScenario(
        tag: String,
        failed: Boolean = false,
        running: Boolean = false,
        watched: Boolean = true,
    ) {
        val app = "com.acme.$tag.App"
        manifest(
            "i-$tag",
            "run-$tag",
            initializerProbes(1, app) +
                probe(1, 3, app, "handle")
                    .toBuilder()
                    .addCalls(
                        CallEdge
                            .newBuilder()
                            .setClassName("com.acme.$tag.Legacy")
                            .setMethodName("apply")
                            .setMethodDescriptor("()V")
                            .setGuard(1),
                    ).build() +
                probe(2, 0, "com.acme.$tag.Legacy", "apply") +
                probe(1, 4, app, "handle", ProbeKind.BRANCH).toBuilder().setBranchIndex(0).build() +
                probe(1, 5, app, "handle", ProbeKind.BRANCH).toBuilder().setBranchIndex(1).build(),
            locations = listOf(location(1), location(2)),
            failed = if (failed) listOf(named(app, InitializationFailure.STATIC_INITIALIZER_THREW)) else emptyList(),
            watched = watched,
        )
        hit("i-$tag", "run-$tag", 1, 0)
        when {
            failed -> hit("i-$tag", "run-$tag", 1, 2, ProbeKind.INITIALIZER_THREW)
            !running -> hit("i-$tag", "run-$tag", 1, 1, ProbeKind.INITIALIZER_RETURNED)
        }
        hit("i-$tag", "run-$tag", 1, 3)
        hit("i-$tag", "run-$tag", 1, 4, ProbeKind.BRANCH)
    }

    @Test
    fun `an untaken outcome is a cluster root only in a class that ran, in a run whose zero counts are evidence`() {
        outcomeScenario("outok")
        outcomeScenario("outfailed", failed = true)
        outcomeScenario("outrunning", running = true)
        outcomeScenario("outblind", watched = false)
        manifest(
            "i-outblind-w",
            "run-outblind-w",
            listOf(probe(1, 0, "com.acme.outblind.Legacy", "apply")),
            locations = listOf(location(1)),
        )

        val clusters = clusterReport()
        val roots = clusters.filter { it.startsWith("UNREACHED CLUSTER: root branch") && it.contains("(untaken outcome)") }
        assertTrue(roots.any { it.contains("outok.App") }, clusters.joinToString("\n"))
        assertTrue(roots.none { it.contains("outfailed.App") }, clusters.joinToString("\n"))
        assertTrue(roots.none { it.contains("outrunning.App") }, clusters.joinToString("\n"))
        assertTrue(roots.none { it.contains("outblind.App") }, clusters.joinToString("\n"))
    }

    @Test
    fun `a method only an unwatched run knows is not a member of another run's cluster`() {
        manifest(
            "i-mem-a",
            "run-mem-a",
            listOf(
                probe(1, 0, "com.acme.mem.Root", "go")
                    .toBuilder()
                    .addCalls(
                        CallEdge
                            .newBuilder()
                            .setClassName("com.acme.mem.Callee")
                            .setMethodName("help")
                            .setMethodDescriptor("()V"),
                    ).build(),
            ),
            locations = listOf(location(1)),
        )
        manifest(
            "i-mem-b",
            "run-mem-b",
            listOf(probe(1, 0, "com.acme.mem.Callee", "help")),
            locations = listOf(location(1)),
            watched = false,
        )

        val clusters = clusterReport()
        assertTrue(clusters.any { it.contains("mem.Root") }, clusters.joinToString("\n"))
        assertTrue(clusters.none { it.contains("mem.Callee") }, clusters.joinToString("\n"))
    }

    @Test
    fun `an interface's own static initialiser is not an instance method, so a failure in it does not carry to implementers`() {
        manifest(
            "i-itfinit",
            "run-itfinit",
            initializerProbes(1, "com.acme.itfinit.Itf") + probe(2, 0, "com.acme.itfinit.Impl", "m"),
            locations = listOf(location(1), location(2).toBuilder().addInterfaceNames("com.acme.itfinit.Itf").build()),
            failed = listOf(named("com.acme.itfinit.Itf", InitializationFailure.STATIC_INITIALIZER_THREW)),
        )

        assertTrue(failedToInitialiseReport().none { it.contains("itfinit.Impl") })
    }

    @Test
    fun `a thrown initialiser in an interface with an instance method carries through an interface that extends it`() {
        manifest(
            "i-chain",
            "run-chain",
            listOf(
                probe(1, 0, "com.acme.chain.Base", "dflt"),
                probe(2, 0, "com.acme.chain.Middle", "other"),
                probe(3, 0, "com.acme.chain.Impl", "m"),
            ),
            locations =
                listOf(
                    location(1),
                    location(2).toBuilder().addInterfaceNames("com.acme.chain.Base").build(),
                    location(3).toBuilder().addInterfaceNames("com.acme.chain.Middle").build(),
                ),
            failed = listOf(named("com.acme.chain.Base", InitializationFailure.STATIC_INITIALIZER_THREW)),
        )

        assertTrue(failedToInitialiseReport().any { it.contains("chain.Impl") }, failedToInitialiseReport().joinToString("\n"))
    }

    @Test
    fun `an endpoint of an unwatched run is not judged never called`() {
        post(
            "manifest",
            ProbeManifest
                .newBuilder()
                .setResource(resource("i-ep-blind", "run-ep-blind"))
                .addProbes(probe(1, 0, "com.acme.epblind.Res", "get"))
                .addClassLocations(location(1))
                .addEndpoints(endpoint("/ep-blind", "com.acme.epblind.Res"))
                .setVerifierRejectionsWatched(false)
                .build(),
        )

        val endpoints = endpointReport()
        assertTrue(endpoints.any { it.contains("NOT JUDGED") && it.contains("/ep-blind") }, endpoints.joinToString("\n"))
    }

    @Test
    fun `an always-supplied parameter of an unwatched run is not reported`() {
        manifest(
            "i-opt",
            "run-opt",
            listOf(
                probe(1, 0, "com.acme.opt.Svc", "call"),
                probe(1, 1, "com.acme.opt.Svc", "call", ProbeKind.OPTIONAL_ARGUMENT)
                    .toBuilder()
                    .setParameterIndex(0)
                    .setParameterName("limit")
                    .build(),
            ),
            locations = listOf(location(1)),
            watched = false,
        )
        hit("i-opt", "run-opt", 1, 0)

        assertTrue(omissionReport().none { it.contains("opt.Svc") }, omissionReport().joinToString("\n"))
    }

    @Test
    fun `an uncalled method of an unwatched run is no cluster root`() {
        manifest(
            "i-clu-blind",
            "run-clu-blind",
            listOf(probe(1, 0, "com.acme.clublind.Lonely", "go")),
            locations = listOf(location(1)),
            watched = false,
        )

        assertTrue(clusterReport().none { it.contains("clublind.Lonely") })
    }

    @Test
    fun `an initialiser entered through one loader stays running though another loader's copy never entered it`() {
        val twice = { id: Int ->
            initializerProbes(id, "com.acme.twice.Twice") + probe(id, 3, "com.acme.twice.Twice", "<init>") +
                probe(id, 4, "com.acme.twice.Twice", "work")
        }
        manifest("i-twice", "run-twice", twice(1) + twice(2), locations = listOf(location(1), location(2)))
        hit("i-twice", "run-twice", 1, 0)

        assertTrue(classFindingReport().none { it.contains("twice.Twice") })
    }

    @Test
    fun `a thrown initialiser carries to implementers only through an interface with a default method`() {
        manifest(
            "i-itf",
            "run-itf",
            listOf(
                probe(1, 0, "com.acme.itf.WithDefault", "dflt"),
                probe(2, 0, "com.acme.itf.OnlyStatic", "util").toBuilder().setStatic(true).build(),
                probe(3, 0, "com.acme.itf.UsesDefault", "m"),
                probe(4, 0, "com.acme.itf.UsesStatic", "m"),
            ),
            locations =
                listOf(
                    location(1),
                    location(2),
                    location(3).toBuilder().addInterfaceNames("com.acme.itf.WithDefault").build(),
                    location(4).toBuilder().addInterfaceNames("com.acme.itf.OnlyStatic").build(),
                ),
            failed =
                listOf(
                    named("com.acme.itf.WithDefault", InitializationFailure.STATIC_INITIALIZER_THREW),
                    named("com.acme.itf.OnlyStatic", InitializationFailure.STATIC_INITIALIZER_THREW),
                ),
        )

        val failed = failedToInitialiseReport()
        assertTrue(failed.any { it.contains("itf.UsesDefault") }, failed.joinToString("\n"))
        assertTrue(failed.none { it.contains("itf.UsesStatic") }, failed.joinToString("\n"))
    }

    @Test
    fun `one unplaced message is named in the singular`() {
        manifest("i-one", "run-one", listOf(probe(1, 0, "com.acme.one.Quiet", "x")), locations = listOf(location(1)), unplaced = 1)

        assertTrue(neverHitReport().any { it.contains("i-one") && it.contains("1 VerifyError message in no read format") })
    }

    @Test
    fun `the dependency report does not judge a library whose only reference comes from an unwatched run`() {
        post(
            "manifest",
            ProbeManifest
                .newBuilder()
                .setResource(resource("i-depblind", "run-depblind"))
                .addProbes(probe(1, 0, "com.acme.depblind.User", "run").toBuilder().addReferencedClasses("org.example.BlindLib").build())
                .addClassLocations(location(1))
                .addDependencies(
                    DependencyLocation
                        .newBuilder()
                        .setDependencyId(0)
                        .addIdentities(
                            DependencyIdentity
                                .newBuilder()
                                .setGroupId("org.example")
                                .setArtifactId("blindlib")
                                .setVersion("1.0"),
                        ).setIdentitySource(DependencyIdentitySource.POM_PROPERTIES)
                        .setLocation("/libs/blindlib.jar")
                        .setDiscoverySource(DependencyDiscoverySource.STARTUP_CLASSPATH)
                        .setClassCount(10),
                ).addExternalClasses(ExternalClass.newBuilder().setClassName("org.example.BlindLib").setDependencyId(0))
                .setReferencesRecorded(true)
                .setDependenciesListed(true)
                .setVerifierRejectionsWatched(false)
                .build(),
        )
        post(
            "deltas",
            DeltaBatch
                .newBuilder()
                .setResource(resource("i-depblind", "run-depblind"))
                .addDependencyDeltas(
                    DependencyDelta
                        .newBuilder()
                        .setDependencyId(0)
                        .setFirstLoadedAt(1L)
                        .setLoadedClassesTotal(1L),
                ).build(),
        )

        val dependencies = dependencyReport()
        assertTrue(dependencies.any { it.contains("NOT JUDGED") && it.contains("blindlib") }, dependencies.joinToString("\n"))
    }

    @Test
    fun `the dependency report reads a library referenced only from a class that failed to initialise as failed to initialise`() {
        post(
            "manifest",
            ProbeManifest
                .newBuilder()
                .setResource(resource("i-dep", "run-dep"))
                .addProbes(probe(1, 0, "com.acme.dep.Broken", "run"))
                .addClassLocations(location(1))
                .addClassesFailedToInitialize(named("com.acme.dep.Broken", InitializationFailure.VERIFIER_REJECTED))
                .addDependencies(
                    DependencyLocation
                        .newBuilder()
                        .setDependencyId(0)
                        .addIdentities(
                            DependencyIdentity
                                .newBuilder()
                                .setGroupId("org.example")
                                .setArtifactId("deplib")
                                .setVersion("1.0"),
                        ).setIdentitySource(DependencyIdentitySource.POM_PROPERTIES)
                        .setLocation("/libs/deplib.jar")
                        .setDiscoverySource(DependencyDiscoverySource.STARTUP_CLASSPATH)
                        .setClassCount(10),
                ).addExternalClasses(ExternalClass.newBuilder().setClassName("org.example.DepLib").setDependencyId(0))
                .addClassReferences(ClassReferences.newBuilder().setClassId(1).addReferencedClasses("org.example.DepLib"))
                .setReferencesRecorded(true)
                .setDependenciesListed(true)
                .setVerifierRejectionsWatched(true)
                .build(),
        )
        post(
            "deltas",
            DeltaBatch
                .newBuilder()
                .setResource(resource("i-dep", "run-dep"))
                .addDependencyDeltas(
                    DependencyDelta
                        .newBuilder()
                        .setDependencyId(0)
                        .setFirstLoadedAt(1L)
                        .setLoadedClassesTotal(1L),
                ).build(),
        )

        val dependencies = dependencyReport()
        assertTrue(dependencies.any { it.contains("FAILED TO INITIALISE") && it.contains("deplib") }, dependencies.joinToString("\n"))
    }

    @Test
    fun `an initialiser still running withholds the class's never-hit rows too`() {
        manifest(
            "i-slow2",
            "run-slow2",
            initializerProbes(1, "com.acme.slow2.Slow") + probe(1, 3, "com.acme.slow2.Slow", "<init>") +
                probe(1, 4, "com.acme.slow2.Slow", "work"),
            locations = listOf(location(1)),
        )
        hit("i-slow2", "run-slow2", 1, 0)

        assertTrue(neverHitReport().none { it.contains("slow2.Slow") })
    }

    @Test
    fun `a class that sends no initialiser slots is initialised by any hit, so a name for it is not listed`() {
        manifest(
            "i-noslot",
            "run-noslot",
            listOf(probe(1, 0, "com.acme.noslot.Quiet", "<clinit>"), probe(1, 1, "com.acme.noslot.Quiet", "run")),
            locations = listOf(location(1)),
            failed = listOf(named("com.acme.noslot.Quiet", InitializationFailure.STATIC_INITIALIZER_THREW)),
        )
        hit("i-noslot", "run-noslot", 1, 1)

        assertTrue(failedToInitialiseReport().none { it.contains("noslot.Quiet") })
    }

    @Test
    fun `a class whose initialiser returned in another run still gets its class finding while one run is still running`() {
        val probes =
            initializerProbes(1, "com.acme.split.Split") + probe(1, 3, "com.acme.split.Split", "<init>") +
                probe(1, 4, "com.acme.split.Split", "work")
        manifest("i-split-a", "run-split-a", probes, locations = listOf(location(1)))
        manifest("i-split-b", "run-split-b", probes, locations = listOf(location(1)))
        hit("i-split-a", "run-split-a", 1, 0)
        hit("i-split-b", "run-split-b", 1, 0)
        hit("i-split-b", "run-split-b", 1, 1, ProbeKind.INITIALIZER_RETURNED)

        assertTrue(classFindingReport().any { it.contains("split.Split") })
    }

    @Test
    fun `a method of a class that failed to initialise is not a member of a caller's cluster`() {
        manifest(
            "i-clu",
            "run-clu",
            listOf(
                probe(1, 0, "com.acme.clu.Root", "go")
                    .toBuilder()
                    .addCalls(
                        CallEdge
                            .newBuilder()
                            .setClassName("com.acme.clu.Broken")
                            .setMethodName("help")
                            .setMethodDescriptor("()V"),
                    ).build(),
                probe(2, 0, "com.acme.clu.Broken", "help"),
            ),
            locations = listOf(location(1), location(2)),
            failed = listOf(named("com.acme.clu.Broken", InitializationFailure.VERIFIER_REJECTED)),
        )

        val clusters = clusterReport()
        assertTrue(clusters.any { it.contains("clu.Root") }, clusters.joinToString("\n"))
        assertTrue(clusters.none { it.contains("clu.Broken") }, clusters.joinToString("\n"))
    }

    @Test
    fun `an endpoint whose handler class failed to initialise is not judged never called`() {
        post(
            "manifest",
            ProbeManifest
                .newBuilder()
                .setResource(resource("i-ep", "run-ep"))
                .addProbes(probe(1, 0, "com.acme.ep.PromoResource", "promo"))
                .addClassLocations(location(1))
                .addClassesFailedToInitialize(named("com.acme.ep.PromoResource", InitializationFailure.VERIFIER_REJECTED))
                .addEndpoints(
                    EndpointLocation
                        .newBuilder()
                        .setEndpointId(1)
                        .setVerb("GET")
                        .setRouteTemplate("/ep-promo")
                        .setVerbatimTemplate("/ep-promo")
                        .setFramework("jaxrs")
                        .setDiscoverySource(EndpointDiscoverySource.REGISTRATION)
                        .setHandlerClass("com.acme.ep.PromoResource"),
                ).setVerifierRejectionsWatched(true)
                .build(),
        )

        val endpoints = endpointReport()
        assertTrue(endpoints.any { it.contains("NOT JUDGED") && it.contains("/ep-promo") }, endpoints.joinToString("\n"))
        assertTrue(endpoints.none { it.contains("NEVER CALLED") && it.contains("/ep-promo") }, endpoints.joinToString("\n"))
    }

    @Test
    fun `a class whose initialiser threw is listed with its subtype and left out of never hit and the class findings`() {
        manifest(
            "i-threw",
            "run-threw",
            initializerProbes(1, "com.acme.threw.Broken") +
                probe(1, 3, "com.acme.threw.Broken", "run") +
                probe(2, 0, "com.acme.threw.Sub", "<init>") +
                probe(2, 1, "com.acme.threw.Sub", "sub") +
                probe(3, 0, "com.acme.threw.Plain", "unused"),
            locations = listOf(location(1), location(2, "com.acme.threw.Broken"), location(3)),
            failed = listOf(named("com.acme.threw.Broken", InitializationFailure.STATIC_INITIALIZER_THREW)),
        )
        hit("i-threw", "run-threw", 1, 0)
        hit("i-threw", "run-threw", 1, 2, ProbeKind.INITIALIZER_THREW)

        val section = failedToInitialiseReport()
        assertTrue("  FAILED TO INITIALISE: com.acme.threw.Broken (STATIC_INITIALIZER_THREW)" in section, section.joinToString("\n"))
        assertTrue(
            "  FAILED TO INITIALISE: com.acme.threw.Sub (subtype of a class that failed to initialise)" in section,
            section.joinToString("\n"),
        )
        val neverHit = neverHitReport()
        assertTrue(neverHit.none { it.contains("threw.Broken") || it.contains("threw.Sub") }, neverHit.joinToString("\n"))
        assertTrue(neverHit.any { it.contains("threw.Plain") }, neverHit.joinToString("\n"))
        val findings = classFindingReport()
        assertTrue(findings.none { it.contains("threw.Broken") || it.contains("threw.Sub") }, findings.joinToString("\n"))
    }

    @Test
    fun `a class whose initialiser threw after calling a helper is failed to initialise, not initialised`() {
        manifest(
            "i-helper",
            "run-helper",
            initializerProbes(1, "com.acme.helper.Broken") + probe(1, 3, "com.acme.helper.Broken", "helper"),
            locations = listOf(location(1)),
            failed = listOf(named("com.acme.helper.Broken", InitializationFailure.STATIC_INITIALIZER_THREW)),
        )
        hit("i-helper", "run-helper", 1, 0)
        hit("i-helper", "run-helper", 1, 3)
        hit("i-helper", "run-helper", 1, 2, ProbeKind.INITIALIZER_THREW)

        val section = failedToInitialiseReport()
        assertTrue("  FAILED TO INITIALISE: com.acme.helper.Broken (STATIC_INITIALIZER_THREW)" in section, section.joinToString("\n"))
    }

    @Test
    fun `a class the verifier rejected is not a never initialised class`() {
        manifest(
            "i-rejected",
            "run-rejected",
            initializerProbes(1, "com.acme.rejected.Rejected") + probe(1, 3, "com.acme.rejected.Rejected", "run"),
            locations = listOf(location(1)),
            failed = listOf(named("com.acme.rejected.Rejected", InitializationFailure.VERIFIER_REJECTED)),
        )

        val findings = classFindingReport()
        assertTrue(findings.none { it.contains("rejected.Rejected") }, findings.joinToString("\n"))
        assertTrue(neverHitReport().none { it.contains("rejected.Rejected") })
        val section = failedToInitialiseReport()
        assertTrue("  FAILED TO INITIALISE: com.acme.rejected.Rejected (VERIFIER_REJECTED)" in section, section.joinToString("\n"))
    }

    @Test
    fun `a class another run initialised is judged from its zero counts and is not listed`() {
        manifest(
            "i-lose",
            "run-lose",
            initializerProbes(1, "com.acme.mixed.Mixed") + probe(1, 3, "com.acme.mixed.Mixed", "run"),
            locations = listOf(location(1)),
            failed = listOf(named("com.acme.mixed.Mixed", InitializationFailure.VERIFIER_REJECTED)),
        )
        manifest(
            "i-win",
            "run-win",
            initializerProbes(1, "com.acme.mixed.Mixed") + probe(1, 3, "com.acme.mixed.Mixed", "run"),
            locations = listOf(location(1)),
        )
        hit("i-win", "run-win", 1, 0)
        hit("i-win", "run-win", 1, 1, ProbeKind.INITIALIZER_RETURNED)

        assertTrue(failedToInitialiseReport().none { it.contains("mixed.Mixed") })
        assertTrue(neverHitReport().any { it.contains("NEVER HIT") && it.contains("mixed.Mixed") && it.contains("i-win") })
    }

    @Test
    fun `an initialiser still running gives no class finding`() {
        manifest(
            "i-slow",
            "run-slow",
            initializerProbes(1, "com.acme.slow.Slow") +
                probe(1, 3, "com.acme.slow.Slow", "<init>") +
                probe(1, 4, "com.acme.slow.Slow", "work"),
            locations = listOf(location(1)),
        )
        hit("i-slow", "run-slow", 1, 0)

        val findings = classFindingReport()
        assertTrue(findings.none { it.contains("slow.Slow") }, findings.joinToString("\n"))
    }

    @Test
    fun `an unwatched run's zero counts are said not to be evidence and are left out of never hit and the class findings`() {
        manifest(
            "i-blind",
            "run-blind",
            listOf(
                probe(1, 0, "com.acme.blind.Quiet", "never"),
                probe(2, 0, "com.acme.blind.Idle", "<init>"),
                probe(2, 1, "com.acme.blind.Idle", "work"),
                probe(3, 0, "com.acme.blind.Ran", "ran"),
                probe(3, 1, "com.acme.blind.Ran", "skipped"),
            ),
            watched = false,
        )
        hit("i-blind", "run-blind", 3, 0)

        val neverHit = neverHitReport()
        assertTrue(
            neverHit.any { it.contains("i-blind") && it.contains("the agent's hook on VerifyError was not installed") },
            neverHit.joinToString("\n"),
        )
        assertTrue(neverHit.none { it.contains("NEVER HIT") && it.contains("com.acme.blind") }, neverHit.joinToString("\n"))
        val findings = classFindingReport()
        assertTrue(findings.any { it.contains("i-blind") && it.contains("not installed") }, findings.joinToString("\n"))
        assertTrue(findings.none { it.contains("blind.Idle") }, findings.joinToString("\n"))
    }

    @Test
    fun `a run with an unplaced verification failure is said not to be evidence and its zero counts are left out`() {
        manifest(
            "i-unplaced",
            "run-unplaced",
            listOf(probe(1, 0, "com.acme.unplaced.Quiet", "never"), probe(1, 1, "com.acme.unplaced.Quiet", "other")),
            unplaced = 2L,
        )

        val neverHit = neverHitReport()
        assertTrue(
            neverHit.any { it.contains("i-unplaced") && it.contains("2 VerifyError messages in no read format") },
            neverHit.joinToString("\n"),
        )
        assertTrue(neverHit.none { it.contains("NEVER HIT") && it.contains("com.acme.unplaced") }, neverHit.joinToString("\n"))
    }

    @Test
    fun `a watched run with no unplaced failure is judged from its zero counts`() {
        manifest("i-seen", "run-seen", listOf(probe(1, 0, "com.acme.seen.Quiet", "never")))

        val neverHit = neverHitReport()
        assertTrue(neverHit.any { it.contains("NEVER HIT") && it.contains("com.acme.seen.Quiet") }, neverHit.joinToString("\n"))
        assertTrue(neverHit.none { it.contains("i-seen") && it.contains("not evidence") })
    }
}
