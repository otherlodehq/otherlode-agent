package dev.otherlode.testkit

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchRole
import dev.otherlode.export.BranchSite
import dev.otherlode.export.CallEdge
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.HttpOtlpStyleExporter
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.RoutineKind
import dev.otherlode.export.StaticBaseline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.otherlode.testkit.ProbeKind as RefProbeKind
import dev.otherlode.testkit.RoutineKind as RefRoutineKind

/**
 * Findings judge the merged hits of every instance by name, as the server does. Every test sends
 * the same code from two instances and checks that a finding names no instance.
 */
class CrossInstanceFindingsTest {
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        collector?.close()
    }

    private fun startCollector(): OtherlodeTestCollector = OtherlodeTestCollector.start().also { collector = it }

    /** Sends [probes] as [instance]'s manifest, then one delta batch with one hit for each of [hits] (class id, probe index). */
    private fun send(
        target: OtherlodeTestCollector,
        instance: String,
        probes: List<ProbeLocation>,
        vararg hits: Pair<Int, Int>,
        hitCount: Long = 1L,
    ) {
        val exporter = HttpOtlpStyleExporter(target.exportUrl)
        val resource = ResourceAttributes("svc", null, instance, null, "run-$instance")
        exporter.exportManifest(ProbeManifest(resource, probes))
        val kinds = probes.associate { (it.classId to it.probeIndex) to it.kind }
        exporter.exportDeltaBatch(
            DeltaBatch(resource, hits.map { (c, p) -> ProbeDelta(c, p, kinds.getValue(c to p), 1L, hitCount) }),
        )
    }

    private fun method(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        line: Int = 1,
        calls: List<CallEdge> = emptyList(),
        inline: Boolean = false,
        branchSites: List<BranchSite> = emptyList(),
        generatedBy: GeneratedBy = GeneratedBy.NONE,
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.METHOD,
        className,
        methodName,
        "()V",
        line,
        null,
        inline = inline,
        calls = calls,
        branchSites = branchSites,
        generatedBy = generatedBy,
    )

    /** One two-outcome site whose outcomes are [taken] and [fallThrough], the first carrying [routine]. */
    private fun site(
        taken: Int,
        fallThrough: Int,
        routine: RoutineKind = RoutineKind.NONE,
    ) = BranchSite(
        0,
        null,
        3,
        listOf(BranchOutcome(taken, BranchRole.TAKEN, routine = routine), BranchOutcome(fallThrough, BranchRole.FALL_THROUGH)),
    )

    /** Sends a complete one-chunk static baseline declaring [classes] as [instance]'s scan. */
    private fun sendBaseline(
        target: OtherlodeTestCollector,
        instance: String,
        classes: List<DeclaredClass>,
    ) {
        val resource = ResourceAttributes("svc", null, instance, null, "run-$instance")
        HttpOtlpStyleExporter(target.exportUrl).exportStaticBaseline(StaticBaseline(resource, classes, scannedAt = 1L))
    }

    private fun branch(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        branchIndex: Int,
        branchKey: String? = null,
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.BRANCH,
        className,
        methodName,
        "()V",
        3,
        branchIndex,
        branchKey = branchKey,
        siteIndex = 0,
    )

    private fun omission(
        classId: Int,
        probeIndex: Int,
        parameterIndex: Int,
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.OPTIONAL_ARGUMENT,
        "com.acme.Svc",
        "greet",
        "()V",
        10,
        null,
        parameterIndex = parameterIndex,
        parameterName = "p$parameterIndex",
    )

    private fun ProbeRef.id() =
        "${className.removePrefix("com.acme.")}#$methodName${if (kind == RefProbeKind.BRANCH) "/$branchIndex" else ""}"

    @Test
    fun `a class one instance constructed and another only loaded has no never-hit row and is not never instantiated`() {
        val target = startCollector()
        val foo =
            listOf(
                method(1, 0, "com.acme.Foo", "<init>"),
                method(1, 1, "com.acme.Foo", "ran"),
                method(1, 2, "com.acme.Foo", "idle"),
            )
        send(target, "i-1", foo, 1 to 0, 1 to 1)
        send(target, "i-2", foo)

        assertEquals(
            listOf("Foo#idle"),
            target.neverHit().map { it.id() },
            "the second instance's zero constructor and zero run are not rows",
        )
        assertEquals(emptyList(), target.neverInstantiated())
        assertEquals(emptyList(), target.neverInitialized())
    }

    @Test
    fun `a class no instance constructed is never instantiated once, counting both instances loading it`() {
        val target = startCollector()
        val foo = listOf(method(1, 0, "com.acme.Foo", "<init>"), method(1, 1, "com.acme.Foo", "run"))
        send(target, "i-1", foo)
        send(target, "i-2", foo)

        val finding = target.neverInstantiated().single()
        assertEquals("com.acme.Foo", finding.className)
        assertEquals(2, finding.instancesLoading)
        assertEquals(emptyList(), target.neverHit(), "the class finding covers both instances' methods")
    }

    @Test
    fun `a method no instance ran is one row, however many instances loaded it`() {
        val target = startCollector()
        val bar = listOf(method(1, 0, "com.acme.Bar", "baz", line = 7))
        send(target, "i-1", bar)
        send(target, "i-2", bar)

        assertEquals(listOf("Bar#baz"), target.neverHit().map { it.id() })
    }

    @Test
    fun `rows take the newest instance's line, and an inline copy in any instance removes the row`() {
        val target = startCollector()
        send(target, "i-1", listOf(method(1, 0, "com.acme.Bar", "baz", line = 7), method(1, 1, "com.acme.Bar", "inl", line = 9)))
        send(
            target,
            "i-2",
            listOf(method(1, 0, "com.acme.Bar", "baz", line = 8), method(1, 1, "com.acme.Bar", "inl", line = 9, inline = true)),
        )

        val rows = target.neverHit()
        assertEquals(listOf("Bar#baz"), rows.map { it.id() })
        assertEquals(8, rows.single().line, "the instance heard from last supplies the line")
    }

    @Test
    fun `outcomes with one branch key and different branch indexes are one row, and a hit in either instance removes it`() {
        val target = startCollector()

        fun probes(
            first: Int,
            second: Int,
        ) = listOf(
            method(1, 0, "com.acme.App", "handle"),
            branch(1, 1, "com.acme.App", "handle", first, branchKey = "ab01"),
            branch(1, 2, "com.acme.App", "handle", second, branchKey = "ab02"),
        )
        send(target, "i-1", probes(0, 1), 1 to 0, 1 to 2)
        send(target, "i-2", probes(4, 5), 1 to 0, 1 to 2)

        val rows = target.neverHit()
        assertEquals(listOf("App#handle/0"), rows.map { it.id() }, "one row, at the lowest index any instance gave it")
        assertEquals("ab01", rows.single().branchKey)

        send(target, "i-3", probes(7, 8), 1 to 0, 1 to 1)
        assertEquals(emptyList(), target.neverHit(), "a hit on the key in a third instance removes the row")
    }

    @Test
    fun `outcomes with no key group by branch index across instances`() {
        val target = startCollector()
        val probes =
            listOf(
                method(1, 0, "com.acme.App", "handle"),
                branch(1, 1, "com.acme.App", "handle", 0),
                branch(1, 2, "com.acme.App", "handle", 1),
            )
        send(target, "i-1", probes, 1 to 0, 1 to 1)
        send(target, "i-2", probes, 1 to 0)

        assertEquals(listOf("App#handle/1"), target.neverHit().map { it.id() }, "index 0 ran in one instance, index 1 in neither")
    }

    @Test
    fun `an optional parameter defaulted in both instances is one never-supplied row`() {
        val target = startCollector()
        val probes = listOf(method(1, 0, "com.acme.Svc", "greet"), omission(1, 1, 0))
        send(target, "i-1", probes, 1 to 0, 1 to 1, hitCount = 3L)
        send(target, "i-2", probes, 1 to 0, 1 to 1, hitCount = 2L)

        assertEquals(listOf("p0"), target.neverSupplied().map { it.parameterName })
        assertEquals(emptyList(), target.alwaysSupplied())
    }

    @Test
    fun `an optional parameter defaulted in one instance and supplied in the other is in neither finding`() {
        val target = startCollector()
        val probes = listOf(method(1, 0, "com.acme.Svc", "greet"), omission(1, 1, 0))
        send(target, "i-1", probes, 1 to 0, 1 to 1, hitCount = 2L)
        send(target, "i-2", probes, 1 to 0, hitCount = 2L)

        assertEquals(emptyList(), target.neverSupplied())
        assertEquals(emptyList(), target.alwaysSupplied())
    }

    @Test
    fun `an optional parameter supplied in both instances is one always-supplied row`() {
        val target = startCollector()
        val probes = listOf(method(1, 0, "com.acme.Svc", "greet"), omission(1, 1, 0))
        send(target, "i-1", probes, 1 to 0)
        send(target, "i-2", probes, 1 to 0)

        assertEquals(listOf("p0"), target.alwaysSupplied().map { it.parameterName })
        assertEquals(emptyList(), target.neverSupplied())
    }

    @Test
    fun `a cluster whose root ran in one instance and not the other is not a cluster`() {
        val target = startCollector()

        fun probes() =
            listOf(
                method(1, 0, "com.acme.App", "handle", calls = listOf(CallEdge("com.acme.Legacy", "run", "()V", false))),
                method(2, 0, "com.acme.Legacy", "run"),
            )
        send(target, "i-1", probes(), 1 to 0)
        assertEquals(1, target.unreachedClusters().size, "with one instance the root never ran")

        send(target, "i-2", probes(), 1 to 0, 2 to 0)
        assertEquals(emptyList(), target.unreachedClusters())
    }

    @Test
    fun `an untaken outcome with one key and different indexes in two instances roots one cluster`() {
        val target = startCollector()

        fun probes(
            untaken: Int,
            taken: Int,
        ) = listOf(
            method(
                1,
                0,
                "com.acme.App",
                "handle",
                calls = listOf(CallEdge("com.acme.Legacy", "run", "()V", false, guard = untaken)),
            ),
            branch(1, 1, "com.acme.App", "handle", untaken, branchKey = "ab01"),
            branch(1, 2, "com.acme.App", "handle", taken, branchKey = "ab02"),
            method(2, 0, "com.acme.Legacy", "run"),
        )
        send(target, "i-1", probes(0, 1), 1 to 0, 1 to 2)
        send(target, "i-2", probes(3, 4), 1 to 0, 1 to 2)

        val cluster = target.unreachedClusters().single()
        assertEquals(RootKind.UNTAKEN_OUTCOME, cluster.rootKind)
        assertEquals("ab01", cluster.root.branchKey)
        assertEquals(listOf("com.acme.Legacy" to "run"), cluster.methods.map { it.className to it.methodName })

        send(target, "i-3", probes(6, 7), 1 to 0, 1 to 1)
        assertTrue(
            target.unreachedClusters().none { it.rootKind == RootKind.UNTAKEN_OUTCOME },
            "a hit on the key in a third instance leaves no untaken outcome",
        )
    }

    @Test
    fun `a root site carries the merged branch indexes the root does`() {
        val target = startCollector()

        fun probes(
            untaken: Int,
            taken: Int,
        ) = listOf(
            method(
                1,
                0,
                "com.acme.App",
                "handle",
                calls = listOf(CallEdge("com.acme.Legacy", "run", "()V", false, guard = untaken)),
                branchSites = listOf(site(untaken, taken)),
            ),
            branch(1, 1, "com.acme.App", "handle", untaken, branchKey = "ab01"),
            branch(1, 2, "com.acme.App", "handle", taken, branchKey = "ab02"),
            method(2, 0, "com.acme.Legacy", "run"),
        )
        send(target, "i-1", probes(0, 1), 1 to 0, 1 to 2)
        send(target, "i-2", probes(3, 4), 1 to 0, 1 to 2)

        val cluster = target.unreachedClusters().single()
        assertEquals(0, cluster.root.branchIndex, "the lowest index any copy gives")
        val site = assertNotNull(cluster.rootSite, "the newest instance listed the site")
        assertEquals(listOf(0, 1), site.outcomes.map { it.branchIndex }, "renumbered from the newest instance's 3 and 4")
        assertEquals(BranchRole.TAKEN, site.outcomes.single { it.branchIndex == cluster.root.branchIndex }.role)
    }

    @Test
    fun `a declared guard resolves the same whichever instance's scan arrives first`() {
        val app =
            DeclaredClass(
                "com.acme.App",
                listOf(DeclaredMethod("handle", "()V", calls = listOf(CallEdge("com.acme.Legacy", "run", "()V", false, guard = 0)))),
            )
        val legacy = DeclaredClass("com.acme.Legacy", listOf(DeclaredMethod("run", "()V")))
        val loaded =
            listOf(
                method(1, 0, "com.acme.App", "handle", calls = listOf(CallEdge("com.acme.Legacy", "run", "()V", false, guard = 0))),
                branch(1, 1, "com.acme.App", "handle", 0, branchKey = "ab01"),
                branch(1, 2, "com.acme.App", "handle", 1, branchKey = "ab02"),
            )
        for (scanFirst in listOf("i-1", "i-2")) {
            val target = startCollector()
            send(target, "i-1", emptyList())
            send(target, "i-2", loaded, 1 to 0, 1 to 2)
            for (instance in listOf(scanFirst, if (scanFirst == "i-1") "i-2" else "i-1")) {
                sendBaseline(target, instance, listOf(app, legacy))
            }

            val cluster = target.unreachedClusters().single()
            assertEquals(RootKind.UNTAKEN_OUTCOME, cluster.rootKind, "with $scanFirst's scan first")
            assertEquals("ab01", cluster.root.branchKey)
            target.close()
        }
    }

    @Test
    fun `a keyed outcome in one instance and a keyless copy in another stay two rows`() {
        val target = startCollector()
        send(
            target,
            "i-1",
            listOf(method(1, 0, "com.acme.App", "handle"), branch(1, 1, "com.acme.App", "handle", 0, branchKey = "ab01")),
            1 to 0,
        )
        send(target, "i-2", listOf(method(1, 0, "com.acme.App", "handle"), branch(1, 1, "com.acme.App", "handle", 0)), 1 to 0)

        assertEquals(listOf("ab01", null), target.neverHit().map { it.branchKey }.sortedBy { it ?: "~" })
    }

    @Test
    fun `a method generated in one instance is no node, even where another reports it unmarked`() {
        val edge = listOf(CallEdge("com.acme.Gen", "copy", "()V", false))
        for (markedFirst in listOf(true, false)) {
            val target = startCollector()
            for (marked in listOf(markedFirst, !markedFirst)) {
                val instance = if (marked == markedFirst) "i-1" else "i-2"
                val gen = method(2, 0, "com.acme.Gen", "copy", generatedBy = if (marked) GeneratedBy.DATA_CLASS else GeneratedBy.NONE)
                send(target, instance, listOf(method(1, 0, "com.acme.App", "handle", calls = edge), gen), 1 to 0)
            }

            assertTrue(
                target.unreachedClusters().none { cluster -> cluster.methods.any { it.className == "com.acme.Gen" } },
                "marked instance first: $markedFirst",
            )
            assertTrue(target.neverHit().none { it.className == "com.acme.Gen" }, "marked instance first: $markedFirst")
            target.close()
        }
    }

    @Test
    fun `an outcome routine in one instance is routine once merged`() {
        fun probes(routine: RoutineKind) =
            listOf(
                method(1, 0, "com.acme.App", "handle", branchSites = listOf(site(0, 1, routine))),
                branch(1, 1, "com.acme.App", "handle", 0, branchKey = "ab01"),
                branch(1, 2, "com.acme.App", "handle", 1, branchKey = "ab02"),
            )
        for (routineFirst in listOf(true, false)) {
            val target = startCollector()
            val kinds =
                if (routineFirst) {
                    listOf(
                        RoutineKind.NULL_DEFAULT,
                        RoutineKind.NONE,
                    )
                } else {
                    listOf(RoutineKind.NONE, RoutineKind.NULL_DEFAULT)
                }
            kinds.forEachIndexed { i, kind -> send(target, "i-${i + 1}", probes(kind), 1 to 0, 1 to 2) }

            assertEquals(emptyList(), target.neverHit(), "routine instance first: $routineFirst")
            assertEquals(
                listOf("ab01" to RefRoutineKind.NULL_DEFAULT),
                target.neverHitRoutineOutcomes().map { it.branchKey to it.routine },
                "routine instance first: $routineFirst",
            )
            target.close()
        }
    }
}
