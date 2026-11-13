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
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.HttpExporter
import dev.otherlode.export.ProbeDelta
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StartingRunTest {
    private val collector = OtherlodeTestCollector.start()
    private val exporter = HttpExporter(collector.exportUrl)
    private val now = AtomicReference(Instant.parse("2026-01-01T00:00:00Z"))
    private var flushSeconds = 0

    init {
        collector.clock = { now.get() }
    }

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun resource(instanceId: String) = ResourceAttributes("svc", null, instanceId, null, "run-$instanceId", flushIntervalSeconds = flushSeconds)

    private fun advance(duration: Duration) = now.updateAndGet { it.plus(duration) }

    private fun batch(
        instanceId: String,
        sequence: Long,
        starting: Boolean,
        complete: Boolean = true,
        finalFlush: Boolean = false,
        pendingSince: Long = 0,
    ) = exporter.exportDeltaBatch(
        DeltaBatch(
            resource(instanceId),
            emptyList(),
            finalFlush = finalFlush,
            payloadSequence = sequence,
            countsPendingSince = pendingSince,
            flushComplete = complete,
            starting = starting,
        ),
    )

    private fun manifest(
        instanceId: String,
        sequence: Long = 0,
        starting: Boolean = false,
        pendingSince: Long = 0,
    ) = exporter.exportManifest(
        watchedManifest(
            resource(instanceId),
            listOf(ProbeLocation(1, 0, ProbeKind.METHOD, "demo.A", "m", "()V", 1, null)),
            payloadSequence = sequence,
            countsPendingSince = pendingSince,
            starting = starting,
        ),
    )

    private fun baseline(
        instanceId: String,
        vararg classes: DeclaredClass,
    ) = exporter.exportStaticBaseline(StaticBaseline(resource(instanceId), classes.toList(), scannedAt = 1000L))

    private fun plain(className: String) = DeclaredClass(className, listOf(DeclaredMethod("run", "()V")))

    private val lib =
        DependencyLocation(
            dependencyId = 0,
            identities = listOf(DependencyIdentity("org.example", "lib", "1.0")),
            identitySource = DependencyIdentitySource.POM_PROPERTIES,
            location = "/libs/lib.jar",
            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 10,
        )

    private fun listLib(
        instanceId: String,
        sequence: Long = 0,
        starting: Boolean = false,
    ) = exporter.exportManifest(
        watchedManifest(
            resource(instanceId),
            emptyList(),
            dependencies = listOf(lib),
            dependenciesListed = true,
            payloadSequence = sequence,
            starting = starting,
        ),
    )

    private fun generated(className: String) = DeclaredClass(className, listOf(DeclaredMethod("run", "()V", generatedBy = GeneratedBy.DATA_CLASS)))

    private fun later(block: () -> Unit) =
        thread(isDaemon = true) {
            Thread.sleep(50)
            block()
        }

    @Test
    fun `awaitSettled does not return while an instance says it is starting, and names it at the timeout`() {
        later {
            batch("i-1", 1, starting = true)
            batch("i-1", 2, starting = true)
            batch("i-1", 3, starting = true)
        }

        val failure = assertFailsWith<TimeoutException> { collector.awaitSettled(Duration.ofMillis(600)) }

        val message = failure.message.orEmpty()
        assertTrue("i-1" in message && "still starting" in message, message)
    }

    @Test
    fun `awaitSettled returns once a later payload drops starting`() {
        later {
            batch("i-1", 1, starting = true)
            batch("i-1", 2, starting = true)
            Thread.sleep(300)
            batch("i-1", 3, starting = false)
            batch("i-1", 4, starting = false)
        }

        val start = System.nanoTime()
        collector.awaitSettled(Duration.ofSeconds(5))

        assertTrue(Duration.ofNanos(System.nanoTime() - start) >= Duration.ofMillis(300))
    }

    @Test
    fun `a manifest that says starting holds the wait until a newer payload clears it`() {
        manifest("i-1", sequence = 1, starting = true)
        val clearedAt = AtomicLong(0)
        later {
            clearedAt.set(System.nanoTime())
            batch("i-1", 2, starting = false)
            batch("i-1", 3, starting = false)
        }

        collector.awaitSettled(Duration.ofSeconds(2))

        assertTrue(clearedAt.get() != 0L && System.nanoTime() >= clearedAt.get(), "the wait returned before the clearing payload was sent")
    }

    @Test
    fun `a payload with sequence 0 does not clear starting`() {
        manifest("i-1", sequence = 1, starting = true)
        later {
            batch("i-1", 0, starting = false)
            batch("i-1", 0, starting = false)
        }

        assertFailsWith<TimeoutException> { collector.awaitSettled(Duration.ofMillis(400)) }
    }

    @Test
    fun `an instance that ended with a complete final flush that says starting is settled`() {
        batch("i-1", 1, starting = true, finalFlush = true)

        collector.awaitSettled(Duration.ofSeconds(2))
    }

    @Test
    fun `a zero-count query refuses while an instance says it is starting, and names it`() {
        manifest("i-1")
        batch("i-1", 1, starting = true)

        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()

        assertTrue("i-1" in message && "still starting" in message, message)
        batch("i-1", 2, starting = false)
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a zero-count query refuses while an instance has sent a manifest and no delta batch`() {
        manifest("i-1")

        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()

        assertTrue("i-1" in message && "no delta batch" in message, message)
        batch("i-1", 1, starting = false)
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a zero-count query ignores an instance known only from a static baseline`() {
        manifest("i-1")
        batch("i-1", 1, starting = false)
        baseline("i-2", plain("demo.Z"))

        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a zero-count query still refuses for an instance that sent a manifest after its baseline and no delta batch`() {
        manifest("i-1")
        batch("i-1", 1, starting = false)
        baseline("i-2", plain("demo.Z"))
        exporter.exportManifest(watchedManifest(resource("i-2"), emptyList()))

        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()

        assertTrue("i-2" in message && "no delta batch" in message, message)
    }

    @Test
    fun `a zero-count query refuses for a dead instance that never sent a delta batch`() {
        manifest("i-1")
        batch("i-2", 1, starting = false, finalFlush = true)

        assertFailsWith<IllegalStateException> { collector.neverHit() }
    }

    @Test
    fun `an instance that ended while starting after delivering its counts no longer holds a zero-count query`() {
        manifest("i-1")
        batch("i-1", 1, starting = true, finalFlush = true)

        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `neverLoaded leaves out the scan of an instance that ended while starting`() {
        batch("i-1", 1, starting = true, finalFlush = true)
        baseline("i-1", plain("demo.Z"))

        assertEquals(emptyList(), collector.neverLoaded())
    }

    @Test
    fun `neverLoaded lists a class from a scan of an instance that ended after starting`() {
        batch("i-1", 1, starting = false, finalFlush = true)
        baseline("i-1", plain("demo.Z"))

        assertEquals(listOf("demo.Z"), collector.neverLoaded())
    }

    @Test
    fun `neverLoaded refuses while a live instance is starting, and while one sent no delta batch`() {
        batch("i-1", 1, starting = true, complete = false)
        baseline("i-1", plain("demo.Z"))
        val starting = assertFailsWith<IllegalStateException> { collector.neverLoaded() }.message.orEmpty()
        assertTrue("i-1" in starting && "still starting" in starting, starting)

        baseline("i-2", plain("demo.Y"))
        batch("i-1", 2, starting = false, complete = false)
        val silent = assertFailsWith<IllegalStateException> { collector.neverLoaded() }.message.orEmpty()
        assertTrue("i-2" in silent && "no delta batch" in silent, silent)

        batch("i-2", 1, starting = false, complete = false)
        assertEquals(listOf("demo.Y", "demo.Z"), collector.neverLoaded())
    }

    @Test
    fun `a class is never loaded when any complete scan declares a judgeable method of it`() {
        batch("i-1", 1, starting = false)
        batch("i-2", 1, starting = false)
        baseline("i-1", generated("demo.X"))
        baseline("i-2", plain("demo.X"))

        assertEquals(listOf("demo.X"), collector.neverLoaded())
    }

    @Test
    fun `a class whose every declared method is generated in every scan is not never loaded`() {
        batch("i-1", 1, starting = false)
        batch("i-2", 1, starting = false)
        baseline("i-1", generated("demo.X"))
        baseline("i-2", generated("demo.X"))

        assertEquals(emptyList(), collector.neverLoaded())
    }

    private fun hitManifest(instanceId: String) =
        exporter.exportManifest(
            watchedManifest(
                resource(instanceId),
                listOf(
                    ProbeLocation(
                        1,
                        0,
                        ProbeKind.METHOD,
                        "demo.A",
                        "run",
                        "()V",
                        1,
                        null,
                        calls = listOf(CallEdge("demo.B", "helper", "()V", false)),
                    ),
                ),
                payloadSequence = 1,
            ),
        )

    private fun hitBatch(
        instanceId: String,
        sequence: Long,
        starting: Boolean,
        finalFlush: Boolean,
    ) = exporter.exportDeltaBatch(
        DeltaBatch(
            resource(instanceId),
            listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 2L)),
            finalFlush = finalFlush,
            payloadSequence = sequence,
            flushComplete = true,
            starting = starting,
        ),
    )

    private fun declaresBAndC(instanceId: String) =
        baseline(
            instanceId,
            DeclaredClass("demo.B", listOf(DeclaredMethod("helper", "()V", calls = listOf(CallEdge("demo.C", "leaf", "()V", false))))),
            DeclaredClass("demo.C", listOf(DeclaredMethod("leaf", "()V"))),
        )

    private fun neverLoadedClusters() = collector.unreachedClusters().filter { it.rootFinding == ClassFinding.NEVER_LOADED }

    @Test
    fun `unreachedClusters builds no never-loaded cluster from the scan of a run that stopped while starting`() {
        hitManifest("i-1")
        hitBatch("i-1", 2, starting = true, finalFlush = true)
        declaresBAndC("i-1")

        assertEquals(emptyList(), collector.neverLoaded())
        assertEquals(emptyList(), neverLoadedClusters())
    }

    @Test
    fun `unreachedClusters builds a never-loaded cluster from the scan of a run that ended after starting`() {
        hitManifest("i-1")
        hitBatch("i-1", 2, starting = false, finalFlush = true)
        declaresBAndC("i-1")

        assertEquals(listOf("demo.B", "demo.C"), collector.neverLoaded())
        assertEquals(listOf("demo.B"), neverLoadedClusters().map { it.root.className })
    }

    @Test
    fun `a class whose scan declares no method is not never loaded`() {
        batch("i-1", 1, starting = false)
        baseline("i-1", DeclaredClass("demo.Empty", emptyList()), plain("demo.Z"))

        assertEquals(listOf("demo.Z"), collector.neverLoaded())
    }

    @Test
    fun `a class whose every method is inline is not never loaded`() {
        batch("i-1", 1, starting = false)
        baseline("i-1", DeclaredClass("demo.Inline", listOf(DeclaredMethod("run", "()V", inline = true))))

        assertEquals(emptyList(), collector.neverLoaded())
    }

    @Test
    fun `a dependency with no class loaded refuses while an instance is starting, then answers`() {
        listLib("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true)

        val one = assertFailsWith<IllegalStateException> { collector.dependency("org.example", "lib") }.message.orEmpty()
        val all = assertFailsWith<IllegalStateException> { collector.unloadedDependencies() }.message.orEmpty()

        assertTrue("i-1" in one && "still starting" in one, one)
        assertTrue("i-1" in all && "still starting" in all, all)
        batch("i-1", 3, starting = false)
        assertEquals(DependencyUsage.UNLOADED, collector.dependency("org.example", "lib").status)
        assertEquals(listOf("org.example:lib"), collector.unloadedDependencies().map { it.identityKey })
    }

    @Test
    fun `unloadedDependencies refuses while an instance sent no delta batch or has pending counts, and only when one would be listed`() {
        listLib("i-1", sequence = 1)
        val noDelta = assertFailsWith<IllegalStateException> { collector.unloadedDependencies() }.message.orEmpty()
        assertTrue("i-1" in noDelta && "no delta batch" in noDelta, noDelta)

        batch("i-1", 2, starting = false, pendingSince = 5)
        val pending = assertFailsWith<IllegalStateException> { collector.unloadedDependencies() }.message.orEmpty()
        assertTrue("i-1" in pending && "counts are pending" in pending, pending)

        batch("i-1", 3, starting = false)
        assertEquals(listOf("org.example:lib"), collector.unloadedDependencies().map { it.identityKey })
    }

    @Test
    fun `unloadedDependencies answers an empty list while an instance is starting when no dependency would be unloaded`() {
        listLib("i-1", sequence = 1, starting = true)
        exporter.exportDeltaBatch(
            DeltaBatch(resource("i-1"), emptyList(), dependencyDeltas = listOf(DependencyDelta(0, 1L, 1L)), payloadSequence = 2, starting = true),
        )

        assertEquals(emptyList(), collector.unloadedDependencies())
    }

    @Test
    fun `a run that went silent while starting stops holding queries after three flush intervals`() {
        flushSeconds = 10
        manifest("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true, complete = false)
        baseline("i-1", plain("demo.Z"))

        advance(Duration.ofSeconds(29))
        assertFailsWith<IllegalStateException> { collector.neverLoaded() }
        assertFailsWith<IllegalStateException> { collector.neverHit() }

        advance(Duration.ofSeconds(1))
        assertEquals(emptyList(), collector.neverLoaded())
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a run that went silent before any delta batch leaves its scan out and still refuses zero-count queries`() {
        flushSeconds = 10
        manifest("i-1", sequence = 1, starting = true)
        baseline("i-1", plain("demo.Z"))

        advance(Duration.ofSeconds(30))

        assertEquals(emptyList(), collector.neverLoaded())
        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()
        assertTrue("i-1" in message && "no delta batch" in message && "still starting" !in message, message)
    }

    @Test
    fun `a run with no flush interval stays live for an hour`() {
        flushSeconds = 0
        manifest("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true, complete = false)

        advance(Duration.ofMinutes(59))
        assertFailsWith<IllegalStateException> { collector.neverHit() }

        advance(Duration.ofMinutes(1))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `only a delta batch extends liveness`() {
        flushSeconds = 10
        manifest("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true, complete = false)
        advance(Duration.ofSeconds(25))
        baseline("i-1", plain("demo.Z"))
        manifest("i-1")

        advance(Duration.ofSeconds(5))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a delta batch extends liveness`() {
        flushSeconds = 10
        manifest("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true, complete = false)
        advance(Duration.ofSeconds(25))
        batch("i-1", 3, starting = true, complete = false)

        advance(Duration.ofSeconds(29))
        assertFailsWith<IllegalStateException> { collector.neverHit() }

        advance(Duration.ofSeconds(1))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `the first valid flush interval applies`() {
        flushSeconds = 0
        manifest("i-1", sequence = 1, starting = true)
        flushSeconds = 10
        batch("i-1", 2, starting = true, complete = false)
        flushSeconds = 20
        batch("i-1", 3, starting = true, complete = false)

        advance(Duration.ofSeconds(30))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `a flush interval over a day counts as none`() {
        flushSeconds = 86_401
        manifest("i-1", sequence = 1, starting = true)
        batch("i-1", 2, starting = true, complete = false)

        advance(Duration.ofMinutes(59))
        assertFailsWith<IllegalStateException> { collector.neverHit() }

        advance(Duration.ofMinutes(1))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `pending counts refuse zero-count queries after the instance ended, and hold awaitSettled`() {
        manifest("i-1", sequence = 1)
        batch("i-1", 5, starting = false, finalFlush = true, pendingSince = 7)

        val message = assertFailsWith<IllegalStateException> { collector.neverHit() }.message.orEmpty()
        assertTrue("i-1" in message && "counts are pending" in message, message)
        val timeout = assertFailsWith<TimeoutException> { collector.awaitSettled(Duration.ofMillis(300)) }.message.orEmpty()
        assertTrue("i-1" in timeout && "counts are pending" in timeout, timeout)

        batch("i-1", 6, starting = false, finalFlush = true)
        collector.awaitSettled(Duration.ofSeconds(2))
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `pending counts clear only on a newer payload by sequence`() {
        manifest("i-1", sequence = 1)
        batch("i-1", 5, starting = false, pendingSince = 7)

        batch("i-1", 4, starting = false)
        assertFailsWith<IllegalStateException> { collector.neverHit() }

        batch("i-1", 6, starting = false)
        assertEquals(listOf("m"), collector.neverHit().map { it.methodName })
    }

    @Test
    fun `pending counts hold a live instance's never-loaded claims, and an ended one's no longer`() {
        batch("i-1", 1, starting = false, pendingSince = 7)
        baseline("i-1", plain("demo.Z"))

        val message = assertFailsWith<IllegalStateException> { collector.neverLoaded() }.message.orEmpty()
        assertTrue("i-1" in message && "counts are pending" in message, message)

        batch("i-1", 2, starting = false, finalFlush = true, pendingSince = 7)
        assertEquals(listOf("demo.Z"), collector.neverLoaded())
    }

    @Test
    fun `an unloaded dependency refuses while the instance that listed it has pending counts, even after it ended`() {
        listLib("i-1", sequence = 1)
        batch("i-1", 2, starting = false, finalFlush = true, pendingSince = 7)

        val message = assertFailsWith<IllegalStateException> { collector.dependency("org.example", "lib") }.message.orEmpty()
        assertTrue("i-1" in message && "counts are pending" in message, message)

        batch("i-1", 3, starting = false, finalFlush = true)
        assertEquals(DependencyUsage.UNLOADED, collector.dependency("org.example", "lib").status)
    }

    @Test
    fun `an unloaded dependency refuses for an instance that listed it and went silent with no delta batch`() {
        flushSeconds = 10
        listLib("i-1", sequence = 1)
        advance(Duration.ofSeconds(31))

        val message = assertFailsWith<IllegalStateException> { collector.unloadedDependencies() }.message.orEmpty()
        assertTrue("i-1" in message && "no delta batch" in message, message)
    }

    @Test
    fun `an unloaded dependency does not wait for an instance that did not list it`() {
        listLib("i-1", sequence = 1)
        batch("i-1", 2, starting = false)
        exporter.exportManifest(watchedManifest(resource("i-2"), emptyList(), dependenciesListed = true, payloadSequence = 1))

        assertEquals(listOf("org.example:lib"), collector.unloadedDependencies().map { it.identityKey })
    }

    @Test
    fun `unreachedClusters refuses while a live instance has sent only its baseline and a class would read never loaded`() {
        hitManifest("i-1")
        hitBatch("i-1", 2, starting = false, finalFlush = true)
        declaresBAndC("i-2")

        val message = assertFailsWith<IllegalStateException> { collector.unreachedClusters() }.message.orEmpty()
        assertTrue("i-2" in message && "no delta batch" in message, message)
    }
}
