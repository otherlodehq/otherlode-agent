package dev.otherlode.markers

import dev.otherlode.registry.FrameworkMarkers
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves each marker in [FrameworkMarkers] before it is listed (ADR 0072), on every release the build
 * pins. Per release, one JVM per scenario, each with `-Xlog:class+load`:
 *
 * - the baseline: the framework's client side, or for a framework with none the framework used
 *   without its routing set up (Javalin: a constant, Undertow: a server answering through a `PathHandler`);
 * - `configured`: routing set up and nothing started;
 * - `serving`: the server up and one request answered.
 *
 * Every class of the marker's set must be absent from the baseline and present when serving.
 * Whether the whole set is present when only configured is the marker's claim: configured, or started.
 */
class FrameworkMarkerFixtureTest {
    private enum class Claim { CONFIGURED, STARTED }

    private class Fixture(
        val markerId: String,
        val framework: String,
        val mainClass: String,
        val baselineMode: String,
        val claim: Claim,
        val alsoAbsent: List<String> = emptyList(),
    )

    private val fixtures =
        listOf(
            Fixture("grpc-server", "grpc", "markers.GrpcMain", "client", Claim.STARTED),
            Fixture("armeria-server", "armeria", "markers.ArmeriaMain", "client", Claim.STARTED),
            Fixture("undertow-routing-handler", "undertow", "markers.UndertowMain", "baseline", Claim.CONFIGURED),
            Fixture("javalin-5", "javalin", "markers.JavalinMain", "baseline", Claim.STARTED),
            Fixture(
                "webflux-router-without-dispatcher",
                "webflux",
                "markers.WebFluxMain",
                "client",
                Claim.CONFIGURED,
                alsoAbsent = listOf("dispatcher"),
            ),
        )

    private val releases: List<String> = ForkedJvm.property("markers.releases").split(",")

    private fun classesOf(markerId: String): List<String> =
        FrameworkMarkers.ALL
            .single { it.id == markerId }
            .classSets
            .single()

    @Test
    fun `every marker the agent lists has a fixture, and every fixture a marker`() {
        assertEquals(FrameworkMarkers.ALL.map { it.id }.sorted(), fixtures.map { it.markerId }.sorted())
        for (fixture in fixtures) {
            assertTrue(releases.any { it.startsWith("${fixture.framework}:") }, "${fixture.markerId} has pinned releases")
        }
    }

    @TestFactory
    fun `each marker is absent from the baseline and present when serving, on every pinned release`(): List<DynamicTest> =
        fixtures.flatMap { fixture ->
            releases.filter { it.startsWith("${fixture.framework}:") }.map { release ->
                val version = release.substringAfter(':')
                DynamicTest.dynamicTest("${fixture.markerId} on ${fixture.framework} $version") { prove(fixture, version) }
            }
        }

    private fun prove(
        fixture: Fixture,
        version: String,
    ) {
        val classpath = ForkedJvm.classpath(fixture.framework, version)
        val marker = classesOf(fixture.markerId)

        fun present(run: ForkedRun) = marker.filter { it in run.loadedClasses }

        val baseline = ForkedJvm.run(classpath, fixture.mainClass, fixture.baselineMode)
        val configured = ForkedJvm.run(classpath, fixture.mainClass, "configured")
        val serving = ForkedJvm.run(classpath, fixture.mainClass, "serving")
        val extra = fixture.alsoAbsent.associateWith { ForkedJvm.run(classpath, fixture.mainClass, it) }

        record(
            "${fixture.markerId} ${fixture.framework} $version: baseline=${present(baseline).size}/${marker.size} " +
                "configured=${present(configured).size}/${marker.size} serving=${present(serving).size}/${marker.size}" +
                extra.entries.joinToString("") { (mode, run) -> " $mode=${present(run).size}/${marker.size}" },
        )

        assertTrue(serving.output.contains("response: ok"), "a request was answered. Output:\n${serving.output}")
        assertEquals(emptyList(), present(baseline), "absent from the ${fixture.baselineMode} run. Output:\n${baseline.output}")
        assertEquals(marker, present(serving), "every class of the set loaded when serving")
        when (fixture.claim) {
            Claim.CONFIGURED -> assertEquals(marker, present(configured), "a configured claim: loaded without starting")
            Claim.STARTED -> assertTrue(present(configured) != marker, "a started claim: the set is incomplete until the server starts")
        }
        for ((mode, run) in extra) {
            assertTrue(run.output.contains("response: ok"), "$mode answered a request. Output:\n${run.output}")
            assertEquals(emptyList(), present(run), "absent from the $mode run")
        }
    }

    @Test
    fun `the Javalin 5 marker class is in no Javalin release a module covers`() {
        val marker = classesOf("javalin-5").first().replace('.', '/') + ".class"
        val covered = ForkedJvm.property("markers.javalinCovered").split(",")
        assertTrue(covered.isNotEmpty())
        for (version in covered) {
            val jar =
                ForkedJvm
                    .property("markers.javalinCovered.$version")
                    .split(File.pathSeparator)
                    .single { File(it).name.startsWith("javalin-$version") }
            ZipFile(jar).use { zip ->
                assertTrue(zip.getEntry(marker) == null, "Javalin $version carries $marker")
                assertTrue(zip.getEntry("io/javalin/Javalin.class") != null, "Javalin $version is the jar read")
            }
        }
    }

    @TestFactory
    fun `a Javalin 4 server never completes the Javalin 5 marker`(): List<DynamicTest> =
        ForkedJvm.property("markers.javalinOlder").split(",").map { version ->
            DynamicTest.dynamicTest("javalin-5 on javalin $version") {
                val marker = classesOf("javalin-5")
                val serving = ForkedJvm.run(ForkedJvm.classpath("javalin-older", version), "markers.JavalinMain", "serving-empty")
                record(
                    "javalin-5 javalin $version (must not match): serving=${marker.count { it in serving.loadedClasses }}/${marker.size}",
                )
                assertTrue(serving.output.contains("response: "), "a request was answered. Output:\n${serving.output}")
                assertTrue(!serving.loadedClasses.containsAll(marker), "Javalin $version completes ${marker.joinToString()}")
            }
        }

    private fun record(line: String) {
        println(line)
        val report = File("build/reports/framework-markers.txt")
        report.parentFile.mkdirs()
        synchronized(FrameworkMarkerFixtureTest::class.java) { report.appendText(line + "\n") }
    }
}
