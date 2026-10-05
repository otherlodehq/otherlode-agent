package dev.otherlode.instrumentation

import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs each fixture program of `:fixtures-probe-window` in a JVM launched with the shaded agent jar,
 * compiled at class-file version 52 and at 61, and checks it runs to completion
 * and that the hits taken while a supertype was still initialising were counted (ADR 0060).
 *
 * Each program lets a supertype's `<clinit>` run the class's own code before the class's `<clinit>`
 * has run: a superclass constant holding a subclass instance, an interface constant holding an
 * anonymous implementation, a superclass calling a static method of its subclass, a Kotlin sealed
 * class and sealed interface whose companion holds a subtype, and an enum constant body initialised
 * by name. Each runs fine without the agent. A probe that read a null array threw
 * `NullPointerException` inside the program and left the class erroneous.
 */
class ProbeArrayWindowTest {
    private companion object {
        const val PACKAGE = "com.example.probewindow"
        const val PROCESS_TIMEOUT_SECONDS = 120L
        val FORMS = listOf("legacy", "modern")
    }

    private class Run(
        val output: String,
        val probes: List<ProbeLocation>,
        private val hits: Map<Pair<Int, Int>, Long>,
    ) {
        fun countsOf(
            className: String,
            methodName: String,
            kind: ProbeKind = ProbeKind.METHOD,
        ): List<Long> =
            probes
                .filter { it.className == "$PACKAGE.$className" && it.methodName == methodName && it.kind == kind }
                .sortedBy { it.probeIndex }
                .map { hits[it.classId to it.probeIndex] ?: 0L }
    }

    private fun run(
        form: String,
        mainClass: String,
        vararg arguments: String,
    ): Run {
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val classes = checkNotNull(System.getProperty("otherlode.fixtures.probewindow.$form.dirs")) { "no $form fixtures" }
        val stdlib = checkNotNull(System.getProperty("otherlode.fixtures.probewindow.kotlinStdlib")) { "no Kotlin stdlib" }
        val outputFile = Files.createTempFile("otherlode-probe-window", ".log").toFile()
        try {
            AgentReceiver().use { receiver ->
                val command =
                    listOf(
                        File(System.getProperty("java.home"), "bin/java").absolutePath,
                        "-javaagent:$agentJar=serviceName=probe-window,includePackages=$PACKAGE," +
                            "flushIntervalSeconds=1,exportUrl=${receiver.endpoint}",
                        "-cp",
                        classes + File.pathSeparator + stdlib,
                        "$PACKAGE.$mainClass",
                    ) + arguments
                val builder = ProcessBuilder(command)
                builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") }
                val process = builder.redirectErrorStream(true).redirectOutput(outputFile).start()
                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    fail("$mainClass ($form) did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${outputFile.readText()}")
                }
                val output = outputFile.readText()
                assertEquals(0, process.exitValue(), "$mainClass ($form) ran to completion. Output:\n$output")
                assertTrue(receiver.failures.isEmpty(), "payloads decode: ${receiver.failures}")
                val hits = HashMap<Pair<Int, Int>, Long>()
                for (delta in receiver.batches.flatMap { it.deltas }) {
                    hits.merge(
                        delta.classId to delta.probeIndex,
                        delta.hitsTotal,
                        ::maxOf,
                    )
                }
                return Run(output, receiver.manifests.flatMap { it.probes }, hits)
            }
        } finally {
            outputFile.delete()
        }
    }

    /** Runs [check] for every form and fails once, naming each form that did not pass. */
    private fun eachForm(check: (form: String) -> Unit) {
        val failures =
            FORMS.mapNotNull { form -> runCatching { check(form) }.exceptionOrNull()?.let { "[$form] ${it.message}" } }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `a superclass constant holding a subclass instance runs to completion and counts the early construction`() =
        eachForm { form ->
            val run = run(form, "SubclassConstantMain")
            assertTrue("area 1" in run.output, "$form output: ${run.output}")
            // One construction inside Shape's initializer, one from main.
            assertEquals(listOf(2L), run.countsOf("Circle", "<init>"), "Circle.<init> in $form. Output:\n${run.output}")
            assertEquals(listOf(1L), run.countsOf("Circle", "area"), "Circle.area in $form")
        }

    @Test
    fun `an interface constant holding an anonymous implementation runs to completion and counts the early construction`() =
        eachForm { form ->
            val run = run(form, "InterfaceConstantMain")
            assertTrue("matches true" in run.output, "$form output: ${run.output}")
            // One construction inside Filter's initializer, one from main.
            assertEquals(listOf(2L), run.countsOf("Filter\$1", "<init>"), "Filter\$1.<init> in $form. Output:\n${run.output}")
            assertEquals(listOf(1L), run.countsOf("Filter\$1", "matches"), "Filter\$1.matches in $form")
        }

    @Test
    fun `a superclass calling a static method of its subclass counts the call and both branch outcomes`() =
        eachForm { form ->
            val run = run(form, "StaticCallMain")
            assertTrue("pick 1" in run.output, "$form output: ${run.output}")
            assertEquals(listOf(2L), run.countsOf("Sub", "pick"), "Sub.pick in $form. Output:\n${run.output}")
            // x > 5 was taken only inside Base's initializer, and not taken only by main.
            assertEquals(listOf(1L, 1L), run.countsOf("Sub", "pick", ProbeKind.BRANCH), "pick's branch outcomes in $form")
        }

    @Test
    fun `an enum constant body initialised by name runs to completion and counts its constructor`() =
        eachForm { form ->
            val run = run(form, "EnumBodyMain")
            assertTrue("apply 5" in run.output, "$form output: ${run.output}")
            // Run once, inside Op's initializer.
            assertEquals(listOf(1L), run.countsOf("Op\$1", "<init>"), "Op\$1.<init> in $form. Output:\n${run.output}")
            assertEquals(listOf(1L), run.countsOf("Op\$1", "apply"), "Op\$1.apply in $form")
        }

    @Test
    fun `a sealed class whose companion holds a subtype runs to completion and counts the early construction`() =
        eachForm { form ->
            val run = run(form, "SealedKt", "result")
            assertTrue("result positive" in run.output, "$form output: ${run.output}")
            assertEquals(listOf(2L), run.countsOf("Success", "<init>"), "Success.<init> in $form. Output:\n${run.output}")
            assertEquals(listOf(1L), run.countsOf("Success", "describe"), "Success.describe in $form")
        }

    @Test
    fun `a sealed interface whose companion holds a subtype runs to completion and counts the early construction`() =
        eachForm { form ->
            val run = run(form, "SealedKt", "token")
            assertTrue("token token" in run.output, "$form output: ${run.output}")
            assertEquals(listOf(2L), run.countsOf("Eof", "<init>"), "Eof.<init> in $form. Output:\n${run.output}")
        }
}
