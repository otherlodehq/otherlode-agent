package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.branch.BrokenScalaFixtures
import dev.otherlode.instrumentation.branch.ScalaFixtures
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Proves the unread shapes of a Scala 3 class through the real transform. `Cc` of the
 * `:fixtures-scala3` output is copied with `hashCode`, `equals` and `copy` changed so they match
 * no shape the agent has read, and its `.tasty` made to name a read release, one the agent has not
 * read, or removed.
 */
class ScalaUnreadShapeMarkingTest {
    private companion object {
        const val PACKAGE = "com.example.scalatarget"
    }

    @TempDir
    lateinit var tempDir: Path

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
    }

    private class Run(
        val probes: List<ProbeLocation>,
        val warnings: List<LogRecord>,
        val counts: UnreadShapeCounts,
    )

    /** Loads `Cc` from a broken copy of the fixtures made with [tooling], once per name in [loaders], and returns what the agent reported. */
    private fun load(
        tooling: String?,
        keepRealTasty: Boolean = false,
        loaders: Int = 1,
    ): Run {
        val classes = BrokenScalaFixtures.copyWith(tempDir.resolve("classes-${System.nanoTime()}").toFile(), tooling, keepRealTasty)
        val registry = ProbeRegistry()
        val counts = UnreadShapeCounts()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry, unreadShapeCounts = counts)
        installedOtherlode = otherlode
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(OtherlodeInstrumentation::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            installedTransformer = otherlode.install(ByteBuddyAgent.install())
            repeat(loaders) {
                Class.forName("$PACKAGE.Cc", true, ScalaFixtures.classLoader("scala3", javaClass.classLoader, classes))
            }
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        val probes =
            registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes.filter { it.className == "$PACKAGE.Cc" }
        return Run(probes, records.filter { it.level == JulLevel.WARNING }, counts)
    }

    private fun List<ProbeLocation>.method(name: String): ProbeLocation = single { it.kind == ProbeKind.METHOD && it.methodName == name }

    @Test
    fun `a broken plumbing body is an unread shape on a release the agent has not read`() {
        val run = load("Scala 3.10.0")

        for (name in BrokenScalaFixtures.BROKEN) {
            val probe = run.probes.first { it.kind == ProbeKind.METHOD && it.methodName == name }
            assertEquals(UnreadShape.CASE_CLASS, probe.unreadShape, name)
            assertEquals(GeneratedBy.NONE, probe.generatedBy, name)
        }
        assertEquals(GeneratedBy.CASE_CLASS, run.probes.method("canEqual").generatedBy, "an intact method is still marked")
        assertEquals(UnreadShape.NONE, run.probes.method("canEqual").unreadShape)
        assertEquals(UnreadShape.NONE, run.probes.method("a").unreadShape, "a field accessor is the adopter's")
    }

    @Test
    fun `the branch probes of an unread method carry its unread shape`() {
        val run = load("Scala 3.10.0")

        val branches = run.probes.filter { it.kind == ProbeKind.BRANCH && it.methodName == "equals" }
        assertTrue(branches.isNotEmpty(), "Cc.equals has branch sites")
        assertTrue(branches.all { it.unreadShape == UnreadShape.CASE_CLASS && it.generatedBy == GeneratedBy.NONE }, branches.toString())
        val others = run.probes.filter { it.kind == ProbeKind.BRANCH && it.methodName != "equals" }
        assertTrue(others.all { it.unreadShape == UnreadShape.NONE }, "only the broken method's branches")
    }

    @Test
    fun `the omission probes of an unread method carry its unread shape`() {
        val run = load("Scala 3.10.0")

        val omissions = run.probes.filter { it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.methodName == "copy" }
        assertEquals(2, omissions.size, "copy's two default getters")
        assertTrue(omissions.all { it.unreadShape == UnreadShape.CASE_CLASS && it.generatedBy == GeneratedBy.NONE }, omissions.toString())
    }

    @Test
    fun `a broken plumbing body is an unread shape when the class has no tasty file`() {
        val run = load(null)

        assertEquals(UnreadShape.CASE_CLASS, run.probes.method("hashCode").unreadShape)
        assertTrue(
            run.probes.filter { it.kind == ProbeKind.BRANCH && it.methodName == "equals" }.all {
                it.unreadShape ==
                    UnreadShape.CASE_CLASS
            },
        )
        assertEquals(emptyList(), run.warnings.map { it.message }, "a version-blind class gets no per-class warning")
        assertEquals(3L, run.counts.total())
        assertEquals(emptyList(), run.counts.unreadReleases())
    }

    @Test
    fun `a broken plumbing body is hand-written on a release the agent has read`() {
        val run = load(null, keepRealTasty = true)

        for (name in BrokenScalaFixtures.BROKEN) {
            val probe = run.probes.first { it.kind == ProbeKind.METHOD && it.methodName == name }
            assertEquals(UnreadShape.NONE, probe.unreadShape, name)
            assertEquals(GeneratedBy.NONE, probe.generatedBy, name)
        }
        assertTrue(run.probes.all { it.unreadShape == UnreadShape.NONE })
        assertEquals(0L, run.counts.total())
        assertEquals(emptyList(), run.warnings)
    }

    @Test
    fun `an unread release logs one warning naming the class, the release and the method count`() {
        val run = load("Scala 3.10.0")

        val warning = run.warnings.single().message
        assertEquals(
            "otherlode: $PACKAGE.Cc was compiled by Scala 3.10.0, which this agent has not read; " +
                "3 methods that look like compiler output are reported as unread shapes",
            warning,
        )
        assertEquals(listOf("3.10.0"), run.counts.unreadReleases())
        assertEquals(
            mapOf(UnreadShape.CASE_CLASS to 3L),
            UnreadShape.entries.associateWith { run.counts.countOf(it) }.filterValues { it > 0 },
        )
    }

    @Test
    fun `the warning is logged once per class even when two loaders define it`() {
        val run = load("Scala 3.10.0", loaders = 2)

        assertEquals(1, run.warnings.count { it.message.contains("$PACKAGE.Cc was compiled by Scala 3.10.0") })
    }
}
