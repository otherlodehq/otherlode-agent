package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.BranchOutcome
import dev.otherlode.export.BranchRole
import dev.otherlode.export.BranchSite
import dev.otherlode.export.ClassListing
import dev.otherlode.export.ConditionPart
import dev.otherlode.export.ConditionPartKind
import dev.otherlode.export.DeclaredClass
import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.Exporter
import dev.otherlode.export.IncludeRules
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import dev.otherlode.export.UnprobedClass
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticBaselinePublisherTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private class RecordingExporter(
        var failOnChunk: Int? = null,
    ) : Exporter {
        val baselines = mutableListOf<StaticBaseline>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            if (baseline.chunkIndex == failOnChunk) throw RuntimeException("collector unreachable")
            baselines += baseline
        }

        override fun exportClassListing(listing: ClassListing) {}
    }

    private fun scanOf(vararg classNames: String) =
        StaticScanResult(
            declaredClasses = classNames.map { DeclaredClass(it, listOf(DeclaredMethod("m", "()V"))) },
            unreadableClasses = emptyList(),
        )

    @Test
    fun `sends every chunk of the scan in order`() {
        val exporter = RecordingExporter()
        val publisher = StaticBaselinePublisher({ scanOf("A", "B", "C") }, exporter, ProbeRegistry(), StaticBaselineMismatchDetector(), 1)

        publisher.run(resource)

        assertEquals(listOf(0, 1, 2), exporter.baselines.map { it.chunkIndex })
        assertTrue(exporter.baselines.all { it.chunkCount == 3 })
        assertEquals(listOf("A", "B", "C"), exporter.baselines.map { it.declaredClasses.single().className })
    }

    @Test
    fun `every chunk carries the include rules the publisher was given`() {
        val exporter = RecordingExporter()
        val rules = IncludeRules(listOf("com.example"), listOf("com.example.internal"))
        val publisher =
            StaticBaselinePublisher(
                { scanOf("A", "B", "C") },
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                1,
                includeRules = rules,
            )

        publisher.run(resource)

        assertEquals(3, exporter.baselines.size)
        assertEquals(setOf<IncludeRules?>(rules), exporter.baselines.map { it.includeRules }.toSet())
    }

    @Test
    fun `a failed chunk stops the send and swallows the failure`() {
        val exporter = RecordingExporter(failOnChunk = 1)
        val publisher = StaticBaselinePublisher({ scanOf("A", "B", "C") }, exporter, ProbeRegistry(), StaticBaselineMismatchDetector(), 1)

        publisher.run(resource)

        assertEquals(listOf(0), exporter.baselines.map { it.chunkIndex })
    }

    @Test
    fun `the chunks a failed send left behind go out, in order, on a later sendPending`() {
        val exporter = RecordingExporter(failOnChunk = 1)
        val sender = StaticBaselineSender(exporter)
        val publisher =
            StaticBaselinePublisher(
                { scanOf("A", "B", "C") },
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                1,
                sender = sender,
            )

        publisher.run(resource)
        assertFalse(sender.sendPending(), "the collector still refuses chunk 1")
        exporter.failOnChunk = null

        assertTrue(sender.sendPending())
        assertEquals(listOf(0, 1, 2), exporter.baselines.map { it.chunkIndex })
        assertTrue(sender.sendPending(), "nothing is left to send")
        assertEquals(3, exporter.baselines.size)
    }

    @Test
    fun `primes the mismatch detector with every class the scan saw, in any bucket`() {
        val detector = StaticBaselineMismatchDetector()
        val scan =
            StaticScanResult(
                declaredClasses = listOf(DeclaredClass("A", listOf(DeclaredMethod("m", "()V")))),
                unreadableClasses = emptyList(),
                unprobedClasses = listOf(UnprobedClass("P", "no concrete methods to probe")),
            )
        val publisher = StaticBaselinePublisher({ scan }, RecordingExporter(), ProbeRegistry(), detector)

        publisher.run(resource)

        assertFalse(detector.shouldWarnAbout("A"))
        assertFalse(detector.shouldWarnAbout("P"), "a class the scan saw but did not declare is not a blind spot")
        assertTrue(detector.shouldWarnAbout("Z"))
    }

    @Test
    fun `sweeps classes that registered before the scan finished, so they are only warned about once`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Early", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val detector = StaticBaselineMismatchDetector()
        val publisher = StaticBaselinePublisher({ scanOf("A") }, RecordingExporter(), registry, detector)

        publisher.run(resource)

        // run() consumed the one-time warning for the early class; a later dynamic check must not repeat it.
        assertFalse(detector.shouldWarnAbout("com.example.Early"))
    }

    private fun captureWarnings(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger(StaticBaselinePublisher::class.java.name)
        val original = logger.level
        logger.addHandler(handler)
        logger.level = Level.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = original
        }
        return records.filter { it.level == Level.WARNING }
    }

    @Test
    fun `a scan holding a class too large for one chunk sends no chunk and logs one warning that names the class and no constant`() {
        val literal = "SECRET-" + "x".repeat(65_528)
        val site =
            BranchSite(
                siteIndex = 0,
                siteKey = null,
                line = 1,
                condition = List(140) { ConditionPart(ConditionPartKind.STRING_LITERAL, literal) },
                outcomes = listOf(BranchOutcome(0, BranchRole.TAKEN)),
            )
        val huge = DeclaredClass("com.acme.Huge", listOf(DeclaredMethod("m", "()V", branchSites = listOf(site))))
        val scan = StaticScanResult(listOf(DeclaredClass("com.acme.Small", listOf(DeclaredMethod("m", "()V"))), huge), emptyList())
        val exporter = RecordingExporter()
        val sender = StaticBaselineSender(exporter)
        val publisher = StaticBaselinePublisher({ scan }, exporter, ProbeRegistry(), StaticBaselineMismatchDetector(), sender = sender)

        val warnings = captureWarnings { publisher.run(resource) }

        assertEquals(emptyList(), exporter.baselines)
        assertTrue(sender.sendPending(), "no chunk was offered")
        assertEquals(emptyList(), exporter.baselines)
        val tooLarge = warnings.filter { it.message.contains("com.acme.Huge") }
        assertEquals(1, tooLarge.size)
        assertTrue(tooLarge.single().message.contains("8 MiB"))
        assertFalse(warnings.any { it.message.contains("SECRET") })
    }
}
