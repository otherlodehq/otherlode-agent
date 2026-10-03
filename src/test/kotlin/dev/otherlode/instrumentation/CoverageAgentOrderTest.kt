package dev.otherlode.instrumentation

import com.sun.net.httpserver.HttpServer
import dev.otherlode.export.ClassLocation
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.RoutineKind
import dev.otherlode.instrumentation.branch.SitePairing
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.internal.data.CRC64
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs one fixture program in three JVMs launched from the command line: with this agent alone,
 * with JaCoCo's agent listed ahead of it, and with JaCoCo's listed after it. The last is Gradle's
 * order when an adopter puts this agent in a test task's `jvmArgs` and applies the `jacoco` plugin.
 *
 * In both orders with JaCoCo, this agent must report the same manifest and the same counts for the
 * fixture classes as it does alone, and JaCoCo must have identified each fixture class by the
 * CRC64 of its class file, which is what JaCoCo's report matches execution data against. Each JVM
 * reports to an HTTP receiver running in this test, and exits, which sends the final flush.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageAgentOrderTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val MAIN_CLASS = "com.example.forked.CoverageAgentOrderMain"
        val FIXTURE_CLASSES =
            listOf("GeneratedPoint", "RoutineTarget", "SwitchTarget", "RoutineJavaTarget", "BranchTarget").map { "$TARGET.$it" }
        val CLASS_DIRS = listOf("build/classes/java/test", "build/classes/kotlin/test")
        const val PROCESS_TIMEOUT_SECONDS = 120L
    }

    /**
     * Shared by every test, since each run is launched once and read by several: JUnit gives a
     * `@TempDir` field a fresh directory per test and deletes the last one, execution data included.
     */
    private val tempDir: Path = Files.createTempDirectory("otherlode-coverage-agent-order")

    @AfterAll
    fun deleteTempDir() {
        tempDir.toFile().deleteRecursively()
    }

    // A launch that fails is remembered as a failure, so a fixture JVM that hangs costs one
    // timeout rather than one per test that reads its run.
    private val aloneRun by lazy { runCatching { launch("alone", jacoco = null) } }
    private val jacocoFirstRun by lazy { runCatching { launch("jacoco-first", jacoco = JacocoPosition.FIRST) } }
    private val otherlodeFirstRun by lazy { runCatching { launch("otherlode-first", jacoco = JacocoPosition.LAST) } }
    private val alone: ForkedRun get() = aloneRun.getOrThrow()
    private val jacocoFirst: ForkedRun get() = jacocoFirstRun.getOrThrow()
    private val otherlodeFirst: ForkedRun get() = otherlodeFirstRun.getOrThrow()

    private enum class JacocoPosition { FIRST, LAST }

    /** What one fixture JVM's agent reported, and where JaCoCo wrote its execution data. */
    private class ForkedRun(
        val name: String,
        val manifests: List<ProbeManifest>,
        val batches: List<DeltaBatch>,
        val execFile: File?,
        val output: String,
    ) {
        val probes: List<ProbeLocation> = manifests.flatMap { it.probes }

        /** The fixture classes' probes, by class, in probe order, with the per-instance class id cleared. */
        fun fixtureProbes(): Map<String, List<ProbeLocation>> =
            probes
                .filter { it.className in FIXTURE_CLASSES }
                .groupBy { it.className }
                .mapValues { (_, list) -> list.sortedBy { it.probeIndex }.map { it.copy(classId = 0) } }

        /** The fixture classes' class records, with the per-instance class id cleared. */
        fun fixtureClassLocations(): Map<String, ClassLocation> {
            val namesById = probes.associate { it.classId to it.className }
            return manifests
                .flatMap { it.classLocations }
                .filter { namesById[it.classId] in FIXTURE_CLASSES }
                .associate { checkNotNull(namesById[it.classId]) to it.copy(classId = 0) }
        }

        /** Every fixture probe's count by class name and probe index, zero for a probe no batch carried. */
        fun fixtureCounts(): Map<Pair<String, Int>, Long> {
            val hits = HashMap<Pair<Int, Int>, Long>()
            for (delta in batches.flatMap { it.deltas }) hits.merge(delta.classId to delta.probeIndex, delta.hitsTotal, ::maxOf)
            return probes
                .filter { it.className in FIXTURE_CLASSES }
                .associate { (it.className to it.probeIndex) to (hits[it.classId to it.probeIndex] ?: 0L) }
        }

        /** JaCoCo's execution data for the fixture package, by internal class name. */
        fun jacocoData(): Map<String, ExecutionData> {
            val file = checkNotNull(execFile) { "$name ran without JaCoCo" }
            assertTrue(file.isFile, "JaCoCo wrote no execution data for $name. Output:\n$output")
            val data = mutableMapOf<String, ExecutionData>()
            file.inputStream().buffered().use { input ->
                val reader = ExecutionDataReader(input)
                reader.setSessionInfoVisitor { }
                reader.setExecutionDataVisitor { if (it.name.startsWith("com/example/target/")) data[it.name] = it }
                reader.read()
            }
            return data
        }
    }

    /** Collects the manifests and delta batches one fixture JVM posts, and answers every post with 200. */
    private class Receiver : AutoCloseable {
        val manifests = CopyOnWriteArrayList<ProbeManifest>()
        val batches = CopyOnWriteArrayList<DeltaBatch>()
        val failures = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        val endpoint: String get() = "http://${server.address.address.hostAddress.let {
            if (':' in it) "[$it]" else it
        }}:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes()
                try {
                    when (exchange.requestURI.path) {
                        "/v1/otherlode/manifest" -> manifests += ProtoPayloadCodec.decodeProbeManifest(body)
                        "/v1/otherlode/deltas" -> batches += ProtoPayloadCodec.decodeDeltaBatch(body)
                    }
                } catch (e: Exception) {
                    failures += "${exchange.requestURI.path}: $e"
                }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun launch(
        name: String,
        jacoco: JacocoPosition?,
    ): ForkedRun {
        val agentJar = checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" }
        val jacocoJar = checkNotNull(System.getProperty("otherlode.jacoco.agentJar")) { "otherlode.jacoco.agentJar is not set" }
        val execFile = jacoco?.let { tempDir.resolve("$name.exec").toFile() }
        val outputFile = tempDir.resolve("$name.log").toFile()
        Receiver().use { receiver ->
            val otherlodeArg =
                "-javaagent:$agentJar=serviceName=coverage-agent-order,includePackages=$TARGET," +
                    "flushIntervalSeconds=1,endpoint=${receiver.endpoint}"
            val jacocoArg = execFile?.let { "-javaagent:$jacocoJar=destfile=${it.absolutePath}" }
            val agentArgs =
                when (jacoco) {
                    null -> listOf(otherlodeArg)
                    JacocoPosition.FIRST -> listOf(checkNotNull(jacocoArg), otherlodeArg)
                    JacocoPosition.LAST -> listOf(otherlodeArg, checkNotNull(jacocoArg))
                }
            val command = listOf(javaExecutable()) + agentArgs + listOf("-cp", forkedClasspath(), MAIN_CLASS)
            val builder = ProcessBuilder(command)
            // Settings in the developer's shell would reach every fixture JVM's agent alike and
            // could hide a difference, so the forks start without them.
            builder.environment().keys.removeIf { it.startsWith("OTHERLODE_") || it.startsWith("OTEL_") }
            val process =
                builder
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile)
                    .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("$name did not exit within $PROCESS_TIMEOUT_SECONDS seconds. Output:\n${outputFile.readText()}")
            }
            val output = outputFile.readText()
            assertEquals(0, process.exitValue(), "$name's exit code. Output:\n$output")
            assertTrue(receiver.failures.isEmpty(), "$name's payloads decode: ${receiver.failures}")
            return ForkedRun(name, receiver.manifests.toList(), receiver.batches.toList(), execFile, output)
        }
    }

    /** The `java` of the JVM running this test, so each fixture JVM runs on the JDK under test. */
    private fun javaExecutable(): String = File(System.getProperty("java.home"), "bin/java").absolutePath

    /**
     * The fixture classes and the Kotlin standard library they link against, and nothing of this
     * agent's own: the agent comes only from its shaded jar, as it does for an adopter.
     */
    private fun forkedClasspath(): String {
        val kotlinStdlib =
            File(
                Unit::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        return (CLASS_DIRS.map { File(it).absolutePath } + kotlinStdlib.absolutePath).joinToString(File.pathSeparator)
    }

    private fun classFileOf(internalName: String): ByteArray =
        CLASS_DIRS
            .map { File(it, "$internalName.class") }
            .first { it.exists() }
            .readBytes()

    @Test
    fun `alone, the agent reports every fixture class with the marks an earlier transformer would hide`() {
        val run = alone
        assertEquals(FIXTURE_CLASSES.toSet(), run.fixtureProbes().keys, "fixture classes reported. Output:\n${run.output}")
        assertTrue(run.probes.any { it.className == "$TARGET.GeneratedPoint" && it.generatedBy == GeneratedBy.DATA_CLASS })
        val routines =
            run.probes
                .filter { it.className in FIXTURE_CLASSES }
                .flatMap { it.branchSites }
                .flatMap { it.outcomes }
                .map { it.routine }
                .toSet()
        assertTrue(RoutineKind.FINALLY_COPY in routines, "a finally copy: $routines")
        assertTrue(RoutineKind.NULL_DEFAULT in routines, "a null-default path: $routines")
        val stringWhen =
            run.probes.single {
                it.className == "$TARGET.SwitchTarget" && it.methodName == "stringWhen" && it.kind == ProbeKind.METHOD
            }
        assertEquals(6, stringWhen.branchSites.sumOf { it.outcomes.size }, "kotlinc's string when recognised")
    }

    /**
     * JaCoCo inverts the jump of `if (flag)` in the Java `tryFinally`, and the fixture program
     * drives it one way, so one of its outcomes has hits and the other none. Under the inversion
     * the received bytes' taken edge is the class file's fall-through.
     */
    @Test
    fun `the Java conditional the fixture drives one way is one JaCoCo inverts`() {
        val method = "tryFinally" to "(Z)I"
        val classFile = classFileOf("com/example/target/RoutineJavaTarget")
        val jacocoBytes =
            Instrumenter(
                OfflineInstrumentationAccessGenerator(),
            ).instrument(classFile, "com/example/target/RoutineJavaTarget")
        assertTrue(
            SitePairing.of(classFile, jacocoBytes, listOf(method)).swappedOrdinalsOf(method.first, method.second).isNotEmpty(),
            "JaCoCo inverts at least one of tryFinally's jumps",
        )
        val counts =
            alone.probes
                .filter { it.className == "$TARGET.RoutineJavaTarget" && it.methodName == method.first && it.kind == ProbeKind.BRANCH }
                .map { alone.fixtureCounts().getValue(it.className to it.probeIndex) }
        assertTrue(counts.any { it == 0L } && counts.any { it > 0L }, "driven one way: $counts")
    }

    @Test
    fun `with JaCoCo listed after this agent, the manifest is the same as alone`() = assertSameManifest(otherlodeFirst)

    @Test
    fun `with JaCoCo listed after this agent, the counts are the same as alone`() = assertSameCounts(otherlodeFirst)

    @Test
    fun `with JaCoCo listed after this agent, JaCoCo identifies each fixture class by its class file`() =
        assertJacocoMatchesClassFiles(otherlodeFirst)

    @Test
    fun `with JaCoCo listed ahead of this agent, the manifest is the same as alone`() = assertSameManifest(jacocoFirst)

    @Test
    fun `with JaCoCo listed ahead of this agent, the counts are the same as alone`() = assertSameCounts(jacocoFirst)

    @Test
    fun `with JaCoCo listed ahead of this agent, JaCoCo identifies each fixture class by its class file`() =
        assertJacocoMatchesClassFiles(jacocoFirst)

    private fun assertSameManifest(with: ForkedRun) {
        val expected = alone.fixtureProbes()
        assertEquals(FIXTURE_CLASSES.toSet(), expected.keys, "fixture classes reported alone. Output:\n${alone.output}")
        for (className in FIXTURE_CLASSES) {
            assertEquals(
                expected[className],
                with.fixtureProbes()[className],
                "$className's probes in ${with.name}. Output:\n${with.output}",
            )
        }
        assertEquals(alone.fixtureClassLocations(), with.fixtureClassLocations(), "class records in ${with.name}")
        val skipped = with.manifests.flatMap { it.skippedClasses }.filter { it.className in FIXTURE_CLASSES }
        assertTrue(skipped.isEmpty(), "no fixture class skipped in ${with.name}: $skipped")
    }

    private fun assertSameCounts(with: ForkedRun) {
        val expected = alone.fixtureCounts()
        assertTrue(expected.values.any { it > 0L }, "the fixture program ran alone. Output:\n${alone.output}")
        assertEquals(expected, with.fixtureCounts(), "counts per probe in ${with.name}. Output:\n${with.output}")
    }

    private fun assertJacocoMatchesClassFiles(with: ForkedRun) {
        val data = with.jacocoData()
        val expectedNames = FIXTURE_CLASSES.map { it.replace('.', '/') }
        assertTrue(data.keys.containsAll(expectedNames), "JaCoCo recorded every fixture class in ${with.name}: ${data.keys}")
        for ((internalName, execution) in data) {
            assertEquals(
                CRC64.classId(classFileOf(internalName)),
                execution.id,
                "JaCoCo's id for $internalName in ${with.name} is its class file's CRC64",
            )
        }
        val routineJava = data.getValue("com/example/target/RoutineJavaTarget")
        assertTrue(routineJava.probes.any { it }, "JaCoCo counted RoutineJavaTarget in ${with.name}")
    }
}
