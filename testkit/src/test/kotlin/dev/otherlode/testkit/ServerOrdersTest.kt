package dev.otherlode.testkit

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchRole
import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.ClassListing
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.DisabledEndpointModule
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointDelta
import dev.otherlode.export.EndpointDiscoverySource
import dev.otherlode.export.EndpointLocation
import dev.otherlode.export.EndpointTemplateKind
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.OutsideCaller
import dev.otherlode.export.OutsideCallerKind
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerOrdersTest {
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        collector?.close()
    }

    private fun startCollector(): OtherlodeTestCollector = OtherlodeTestCollector.start().also { collector = it }

    private fun resource(instance: String) = ResourceAttributes("svc", null, instance, null, "run-$instance")

    private fun method(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        descriptor: String = "()V",
        line: Int = 1,
        calls: List<CallEdge> = emptyList(),
        branchSites: List<BranchSite> = emptyList(),
        static: Boolean = false,
        lambdaBody: Boolean = false,
        outsideCaller: OutsideCaller? = null,
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.METHOD,
        className,
        methodName,
        descriptor,
        line,
        null,
        calls = calls,
        branchSites = branchSites,
        static = static,
        lambdaBody = lambdaBody,
        outsideCaller = outsideCaller,
    )

    private fun branch(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        branchIndex: Int,
        siteIndex: Int,
        line: Int = 3,
        descriptor: String = "()V",
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.BRANCH,
        className,
        methodName,
        descriptor,
        line,
        branchIndex,
        siteIndex = siteIndex,
    )

    private fun site(
        siteIndex: Int,
        taken: Int,
        fallThrough: Int,
    ) = BranchSite(
        siteIndex,
        null,
        3,
        listOf(BranchOutcome(taken, BranchRole.TAKEN), BranchOutcome(fallThrough, BranchRole.FALL_THROUGH)),
    )

    private fun call(
        className: String,
        methodName: String,
        descriptor: String = "()V",
    ) = CallEdge(className, methodName, descriptor, virtual = false)

    private fun creates(
        className: String,
        methodName: String,
    ) = CallEdge(className, methodName, "()V", virtual = false, kind = CallEdgeKind.CREATES)

    private fun send(
        target: OtherlodeTestCollector,
        instance: String,
        probes: List<ProbeLocation>,
        vararg hits: Pair<Int, Int>,
        endpoints: List<EndpointLocation> = emptyList(),
        endpointDeltas: List<EndpointDelta> = emptyList(),
        disabled: List<DisabledEndpointModule> = emptyList(),
    ) {
        val exporter = HttpExporter(target.exportUrl)
        val resource = resource(instance)
        exporter.exportManifest(watchedManifest(resource, probes, endpoints = endpoints, disabledEndpointModules = disabled))
        val kinds = probes.associate { (it.classId to it.probeIndex) to it.kind }
        exporter.exportDeltaBatch(
            DeltaBatch(
                resource,
                hits.map { (c, p) -> ProbeDelta(c, p, kinds.getValue(c to p), 1L, 1L) },
                endpointDeltas = endpointDeltas,
            ),
        )
    }

    private fun endpoint(
        id: Int,
        verb: String,
        template: String,
        kind: EndpointTemplateKind = EndpointTemplateKind.PATH,
    ) = EndpointLocation(
        endpointId = id,
        verb = verb,
        routeTemplate = template,
        verbatimTemplate = template,
        framework = "jdk-httpserver",
        discoverySource = EndpointDiscoverySource.REGISTRATION,
        handlerClass = null,
        handlerMethod = null,
        handlerDescriptor = null,
        templateKind = kind,
    )

    private val overridesRunnable = OutsideCaller(OutsideCallerKind.OVERRIDES_METHOD, "java.lang.Runnable")

    @Test
    fun `a root takes its outside caller from the newest copy that has one, not the newest copy`() {
        val target = startCollector()
        send(target, "i-1", listOf(method(1, 0, "com.acme.Task", "run", outsideCaller = overridesRunnable)))
        send(target, "i-2", listOf(method(1, 0, "com.acme.Task", "run")))

        val cluster = target.unreachedClusters().single()
        assertEquals(RootKind.CALLED_FROM_OUTSIDE_SCOPE, cluster.rootKind)
        assertEquals(OutsideCallerKind.OVERRIDES_METHOD.name, cluster.root.outsideCaller?.kind?.name)
        assertEquals("java.lang.Runnable", cluster.root.outsideCaller?.typeName)
    }

    @Test
    fun `a never-hit lambda created only by another class's covered method joins that class's cluster, not its own class's finding`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "main"),
                method(2, 0, "com.acme.A", "<init>"),
                method(2, 1, "com.acme.A", "a"),
                method(2, 2, "com.acme.A", "lambda\$0", static = true, lambdaBody = true),
                method(3, 0, "com.acme.B", "<init>"),
                method(3, 1, "com.acme.B", "m", calls = listOf(creates("com.acme.A", "lambda\$0"))),
            ),
            1 to 0,
        )

        assertEquals(listOf("com.acme.A", "com.acme.B"), target.neverInstantiated().map { it.className })
        val clusters = target.unreachedClusters()
        val cluster = clusters.single()
        assertEquals("com.acme.B", cluster.root.className)
        assertEquals(RootKind.CLASS_FINDING, cluster.rootKind)
        assertEquals(listOf("com.acme.A#lambda\$0"), cluster.members.map { "${it.className}#${it.methodName}" })
    }

    @Test
    fun `a never-hit lambda created only by its own class's covered method folds into that class's finding`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "main"),
                method(2, 0, "com.acme.A", "<init>"),
                method(2, 1, "com.acme.A", "a", calls = listOf(creates("com.acme.A", "lambda\$0"))),
                method(2, 2, "com.acme.A", "lambda\$0", static = true, lambdaBody = true),
            ),
            1 to 0,
        )

        assertEquals(emptyList(), target.neverHit())
        assertEquals(emptyList(), target.unreachedClusters())
    }

    @Test
    fun `a never-hit lambda folds into its own class's finding when its creator chain ends in that class`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "main"),
                method(2, 0, "com.acme.A", "<init>"),
                method(2, 1, "com.acme.A", "a", calls = listOf(creates("com.acme.B", "lambda\$0"))),
                method(2, 2, "com.acme.A", "lambda\$1", static = true, lambdaBody = true),
                method(3, 0, "com.acme.B", "lambda\$0", static = true, lambdaBody = true, calls = listOf(creates("com.acme.A", "lambda\$1"))),
            ),
            1 to 0,
        )

        assertEquals(emptyList(), target.neverHit())
        assertEquals(listOf("com.acme.A"), target.neverInstantiated().map { it.className })
        assertEquals(emptyList(), target.unreachedClusters())
    }

    @Test
    fun `a never-hit lambda whose creator chains end in two classes folds into neither finding`() {
        for (creators in listOf(listOf("com.acme.A" to "a", "com.acme.B" to "b"), listOf("com.acme.B" to "b", "com.acme.A" to "a"))) {
            val target = startCollector()
            val creating = creators.mapIndexed { i, (className, methodName) ->
                method(if (className == "com.acme.A") 2 else 3, 1 + i, className, methodName, calls = listOf(creates("com.acme.A", "lambda\$0")))
            }
            send(
                target,
                "i-1",
                listOf(
                    method(1, 0, "com.acme.Main", "main"),
                    method(2, 0, "com.acme.A", "<init>"),
                    method(2, 9, "com.acme.A", "lambda\$0", static = true, lambdaBody = true),
                    method(3, 0, "com.acme.B", "<init>"),
                ) + creating,
                1 to 0,
            )

            assertEquals(emptyList(), target.unreachedClusters(), "creators in order $creators")
            target.close()
        }
    }

    @Test
    fun `a never-hit lambda with a creator outside any class finding folds into no finding`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "main", calls = listOf(call("com.acme.Main", "used"))),
                method(1, 1, "com.acme.Main", "used"),
                method(2, 0, "com.acme.A", "<init>"),
                method(2, 1, "com.acme.A", "a", calls = listOf(creates("com.acme.A", "lambda\$0"))),
                method(2, 2, "com.acme.A", "lambda\$0", static = true, lambdaBody = true),
                method(4, 0, "com.acme.D", "<init>", calls = listOf(call("com.acme.Main", "used"))),
                method(4, 1, "com.acme.D", "x", calls = listOf(creates("com.acme.A", "lambda\$0"))),
            ),
            1 to 0,
            1 to 1,
            4 to 0,
        )

        assertEquals(listOf("com.acme.D#x"), target.neverHit().map { "${it.className}#${it.methodName}" })
        val cluster = target.unreachedClusters().single()
        assertEquals(listOf("com.acme.D#x"), cluster.members.map { "${it.className}#${it.methodName}" })
        assertEquals(emptyList(), cluster.wholeClasses)
    }

    @Test
    fun `never-hit lambdas that only create each other are never-hit rows`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "main"),
                method(5, 0, "com.acme.E", "lambda\$0", static = true, lambdaBody = true, calls = listOf(creates("com.acme.E", "lambda\$1"))),
                method(5, 1, "com.acme.E", "lambda\$1", static = true, lambdaBody = true, calls = listOf(creates("com.acme.E", "lambda\$0"))),
            ),
            1 to 0,
        )

        assertEquals(listOf("lambda\$0", "lambda\$1"), target.neverHit().filter { it.className == "com.acme.E" }.map { it.methodName })
    }

    @Test
    fun `endpoints sort by verb, then template, then path, regex and servlet pattern`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            emptyList(),
            endpoints =
                listOf(
                    endpoint(0, "GET", "/b"),
                    endpoint(1, "POST", "/a"),
                    endpoint(2, "GET", "/a"),
                    endpoint(3, "GET", "/c", EndpointTemplateKind.SERVLET_PATTERN),
                    endpoint(4, "GET", "/c", EndpointTemplateKind.REGEX),
                    endpoint(5, "GET", "/c"),
                ),
        )

        val expected =
            listOf(
                Triple("GET", "/a", "path"),
                Triple("GET", "/b", "path"),
                Triple("GET", "/c", "path"),
                Triple("GET", "/c", "regex"),
                Triple("GET", "/c", "servlet"),
                Triple("POST", "/a", "path"),
            )

        fun EndpointRef.shape() = Triple(verb, routeTemplate, if (isRegexTemplate) "regex" else if (isServletPatternTemplate) "servlet" else "path")
        assertEquals(expected, target.endpoints().map { it.shape() })
        assertEquals(expected, target.neverCalled().map { it.shape() })
    }

    @Test
    fun `never-hit rows sort by class, method, descriptor, kind, line, then branch index`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Z", "m", "(Z)V", line = 1),
                method(1, 1, "com.acme.Z", "m", "(I)V", line = 9),
                method(1, 2, "com.acme.Z", "hit", line = 20, branchSites = listOf(site(0, 3, 4))),
                branch(1, 3, "com.acme.Z", "hit", 0, 0, line = 21),
                branch(1, 4, "com.acme.Z", "hit", 1, 0, line = 21),
                method(2, 0, "com.acme.A", "x", line = 50),
            ),
            1 to 2,
        )

        assertEquals(
            listOf(
                Triple("com.acme.A", "x", "()V"),
                Triple("com.acme.Z", "hit", "()V"),
                Triple("com.acme.Z", "hit", "()V"),
                Triple("com.acme.Z", "m", "(I)V"),
                Triple("com.acme.Z", "m", "(Z)V"),
            ),
            target.neverHit().map { Triple(it.className, it.methodName, it.methodDescriptor) },
        )
        assertEquals(listOf(null, 0, 1, null, null), target.neverHit().map { it.branchIndex })
    }

    @Test
    fun `cluster members and reachedFrom sort by class, method and descriptor, not line`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(1, 0, "com.acme.Main", "go", "(Z)V", line = 1, calls = listOf(call("com.acme.Root", "root"))),
                method(1, 1, "com.acme.Main", "go", "(I)V", line = 90, calls = listOf(call("com.acme.Root", "root"))),
                method(
                    2,
                    0,
                    "com.acme.Root",
                    "root",
                    calls = listOf(call("com.acme.Other", "aa", "(Z)V"), call("com.acme.Other", "aa", "(I)V"), call("com.acme.Root", "beta")),
                ),
                method(2, 1, "com.acme.Root", "beta", line = 5),
                method(3, 0, "com.acme.Other", "aa", "(Z)V", line = 5),
                method(3, 1, "com.acme.Other", "aa", "(I)V", line = 80),
                method(3, 2, "com.acme.Other", "kept", line = 40),
            ),
            1 to 0,
            1 to 1,
            3 to 2,
        )

        fun List<ProbeRef>.ids() = map { "${it.methodName}${it.methodDescriptor}" }
        val cluster = target.unreachedClusters().single()
        assertEquals(listOf("go(I)V", "go(Z)V"), cluster.reachedFrom.ids())
        assertEquals(listOf("aa(I)V", "aa(Z)V"), cluster.members.ids())
        assertEquals(listOf("beta()V", "root()V"), cluster.wholeClasses.single().methods.ids())
        assertEquals(listOf("aa(I)V", "aa(Z)V", "beta()V", "root()V"), cluster.methods.ids())
    }

    @Test
    fun `clusters of equal size sort by root class, method and descriptor, then outcome by branch index`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(
                method(
                    1,
                    0,
                    "com.acme.Main",
                    "handle",
                    calls = listOf(CallEdge("com.acme.C", "zero", "()V", virtual = false, guard = 0), CallEdge("com.acme.C", "one", "()V", virtual = false, guard = 1)),
                    branchSites = listOf(site(0, 1, 2)),
                ),
                branch(1, 1, "com.acme.Main", "handle", 0, 0, line = 50),
                branch(1, 2, "com.acme.Main", "handle", 1, 0, line = 10),
                method(2, 0, "com.acme.Late", "z", line = 1),
                method(3, 0, "com.acme.Early", "late", line = 99),
                method(3, 1, "com.acme.Early", "early", "(I)V", line = 2),
                method(3, 2, "com.acme.Early", "early", "()V", line = 1),
                method(4, 0, "com.acme.C", "zero"),
                method(4, 1, "com.acme.C", "one"),
                method(4, 2, "com.acme.C", "other"),
            ),
            1 to 0,
            4 to 2,
        )

        val roots =
            target.unreachedClusters().map { Triple(it.root.className, it.root.methodName + it.root.methodDescriptor, it.root.branchIndex) }
        assertEquals(
            listOf(
                Triple("com.acme.Early", "early()V", null),
                Triple("com.acme.Early", "early(I)V", null),
                Triple("com.acme.Early", "late()V", null),
                Triple("com.acme.Late", "z()V", null),
                Triple("com.acme.Main", "handle()V", 0),
                Triple("com.acme.Main", "handle()V", 1),
            ),
            roots,
        )
    }

    @Test
    fun `an instance's first report of a disabled module stays when it reports the module again`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            emptyList(),
            disabled = listOf(DisabledEndpointModule("spring-mvc", "first reason", 1L, DisabledEndpointModuleKind.LINKAGE_ERROR)),
        )
        send(
            target,
            "i-1",
            emptyList(),
            disabled = listOf(DisabledEndpointModule("spring-mvc", "second reason", 1L, DisabledEndpointModuleKind.ADVICE_FAILED)),
        )

        assertEquals("first reason", target.disabledEndpointModules().single().reason)
    }

    @Test
    fun `the newest instance's report of a disabled module wins`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            emptyList(),
            disabled = listOf(DisabledEndpointModule("spring-mvc", "old reason", 1L, DisabledEndpointModuleKind.LINKAGE_ERROR)),
        )
        send(
            target,
            "i-2",
            emptyList(),
            disabled = listOf(DisabledEndpointModule("spring-mvc", "new reason", 1L, DisabledEndpointModuleKind.ADVICE_FAILED)),
        )

        val module = target.disabledEndpointModules().single()
        assertEquals("new reason", module.reason)
        assertEquals(DisabledEndpointModuleKind.ADVICE_FAILED.name, module.kind?.name)
    }

    @Test
    fun `a class listing that arrives first ranks its instance as a manifest would`() {
        val target = startCollector()
        val exporter = HttpExporter(target.exportUrl)
        exporter.exportClassListing(ClassListing(resource("i-old"), listOf("com.acme.Task"), IncludeRules(emptyList(), emptyList()), listedAt = 1L))
        send(target, "i-new", listOf(method(1, 0, "com.acme.Task", "run", line = 8)))
        send(target, "i-old", listOf(method(1, 0, "com.acme.Task", "run", line = 4)))

        assertEquals(8, target.neverHit().single().line)
    }
}
