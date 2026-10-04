package dev.otherlode.benchmark

import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.VersionedFixtures
import dev.otherlode.registry.ProbeRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the bytes the agent writes, since ByteBuddy's type validation is off. A woven class may
 * fail to define or initialise only where the same class, unwoven, fails too. A class that defines
 * unwoven and not woven is the agent's fault, and the JVM's verifier and class-file parser say so
 * where validation would have guessed.
 *
 * Two inputs. Every class of the five benchmark corpora is woven through the real transformer, then
 * defined and initialised woven and unwoven, each in a loader of its own over the corpus's
 * classpath. And a matrix of generated classes at class-file versions 45 to 69 covers the shapes
 * the corpora, all compiled for recent versions, never contain: interfaces and annotation types
 * with a static initialiser, subroutines, annotations below version 49. Each of those is woven,
 * defined, initialised and called, woven and unwoven, and the woven copy must also count.
 */
class WovenClassVerificationTest {
    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    /** What running one class did: its [result], or the [error] it ended in. */
    private data class Outcome(
        val result: List<String>,
        val error: String?,
        val rootType: Class<*>? = null,
    ) {
        val ok get() = error == null

        /** A failure the agent's bytes would cause, rather than one the class brings with it. */
        val isMalformed get() = rootType == VerifyError::class.java || rootType == ClassFormatError::class.java

        override fun toString(): String = error ?: result.toString()
    }

    private fun attempt(block: () -> List<String>): Outcome =
        try {
            Outcome(block(), null)
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            Outcome(
                emptyList(),
                "${t.javaClass.simpleName}: ${t.message?.take(MESSAGE_WIDTH)} (root ${root.javaClass.simpleName})",
                root.javaClass,
            )
        }

    /** What weaving and running one corpus found: how many classes wove, ran in both forms or failed in both, and the violations. */
    private class CorpusResult(
        val corpus: String,
        val classes: Int,
        val woven: Int,
        val bothRan: Int,
        val bothFailed: Int,
        val violations: List<String>,
    )

    /** A loader that defines [name] from [bytes] and takes every other class from [parent]. */
    private class DefineOneLoader(
        parent: ClassLoader,
        private val name: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        override fun loadClass(
            requested: String,
            resolve: Boolean,
        ): Class<*> {
            if (requested != name) return super.loadClass(requested, resolve)
            synchronized(getClassLoadingLock(requested)) {
                return findLoadedClass(requested) ?: defineClass(requested, bytes, 0, bytes.size)
            }
        }
    }

    private fun sweep(corpusName: String): CorpusResult {
        val corpus = BenchmarkCorpus.load(corpusName)
        var woven = 0
        var bothRan = 0
        var bothFailed = 0
        val violations = mutableListOf<String>()
        for (set in corpus.classSets) {
            val classpath =
                checkNotNull(
                    System.getProperty("$CLASSPATH_PROPERTY$corpusName.${set.name}"),
                ) { "no classpath for $corpusName.${set.name}" }
            val corpusPath = checkNotNull(System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.${set.name}"))
            val urls =
                (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
                    .filter { it.isNotEmpty() }
                    .map { File(it).toURI().toURL() }
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { shared ->
                val transformer = HotPathWeaver.offlineTransformer(corpus.includePackages, ProbeRegistry())
                for ((internalName, original) in set.classes) {
                    val wovenBytes =
                        try {
                            transformer.transform(shared, internalName, null, null, original)
                        } catch (t: Throwable) {
                            // The one reason a class may fail to weave: a type its classpath lacks (ADR 0059 is to
                            // describe those). Anything else is a weave the agent got wrong.
                            val root = generateSequence(t) { it.cause }.map { it.message.orEmpty() }.joinToString(" <- ")
                            if (UNRESOLVED !in root) violations += "${set.name}:$internalName failed to weave: $root"
                            null
                        } ?: continue
                    woven++
                    val name = internalName.replace('/', '.')
                    val unwovenOutcome = attempt { initialise(DefineOneLoader(shared, name, original), name) }
                    val wovenOutcome = attempt { initialise(DefineOneLoader(shared, name, wovenBytes), name) }
                    when {
                        wovenOutcome.isMalformed && !unwovenOutcome.isMalformed -> {
                            violations += "${set.name}:$name woven bytes are malformed: $wovenOutcome; unwoven: $unwovenOutcome"
                        }

                        wovenOutcome.ok && unwovenOutcome.ok -> {
                            bothRan++
                        }

                        !wovenOutcome.ok && !unwovenOutcome.ok && wovenOutcome.rootType == unwovenOutcome.rootType -> {
                            bothFailed++
                        }

                        !wovenOutcome.ok -> {
                            violations += "${set.name}:$name woven: $wovenOutcome; unwoven: $unwovenOutcome"
                        }

                        else -> {
                            bothRan++
                        }
                    }
                }
            }
        }
        return CorpusResult(corpusName, corpus.classCount, woven, bothRan, bothFailed, violations)
    }

    private fun initialise(
        loader: ClassLoader,
        name: String,
    ): List<String> {
        Class.forName(name, true, loader)
        return emptyList()
    }

    @Test
    fun `every woven class of the benchmark corpora defines and initialises wherever its unwoven twin does`() {
        val started = System.nanoTime()
        val results = CORPORA.map { sweep(it) }
        println(
            "woven-class verification, corpora: " +
                results.joinToString("; ") {
                    "${it.corpus} classes=${it.classes} woven=${it.woven} bothRan=${it.bothRan} bothFailed=${it.bothFailed}"
                } + " (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms)",
        )
        for (r in results) {
            assertTrue(
                r.violations.isEmpty(),
                "${r.corpus}: woven classes that fail where the unwoven class does not:\n  ${r.violations.joinToString("\n  ")}",
            )
            // Floors a little under what each corpus gave on 2026-10-04, so a weave or a run that quietly stops
            // happening fails here rather than shrinking what the check covers.
            val (minWoven, minRan) = FLOORS.getValue(r.corpus)
            assertTrue(r.woven >= minWoven, "${r.corpus}: only ${r.woven} of ${r.classes} classes were woven, expected at least $minWoven")
            assertTrue(r.bothRan >= minRan, "${r.corpus}: only ${r.bothRan} of ${r.woven} woven classes ran, expected at least $minRan")
        }
    }

    /**
     * A loader that serves [resources] as class files and [definitions] as the bytes it defines
     * classes from. The agent reads a class file as a resource, so weaving through a loader with
     * only the originals in [resources] reads the compiler's bytes, and the woven ones can then be
     * put in [definitions] for the same loader to define.
     */
    private class MatrixLoader(
        parent: ClassLoader,
        private val resources: Map<String, ByteArray>,
        val definitions: MutableMap<String, ByteArray>,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val bytes = definitions[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name.removeSuffix(".class")]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)
    }

    /** One generated shape: its classes, how to run it, and what its probes must have counted. */
    private class Case(
        val label: String,
        val classes: List<VersionedFixtures.Fixture>,
        val drive: (ClassLoader) -> List<String>,
        val expect: (Counts) -> Unit,
    )

    /** The hits of one run, read from the registry the woven classes counted into. */
    private class Counts(
        private val probes: List<ProbeLocation>,
        private val hits: Map<Pair<Int, Int>, Long>,
    ) {
        private fun hitsOf(probe: ProbeLocation): Long = hits[probe.classId to probe.probeIndex] ?: 0L

        private fun of(
            className: String,
            methodName: String,
            kind: ProbeKind,
        ) = probes.filter { it.className == className && it.methodName == methodName && it.kind == kind }

        /** How often [methodName] of [className] was entered. */
        fun entries(
            className: String,
            methodName: String,
        ): Long = of(className, methodName, ProbeKind.METHOD).sumOf { hitsOf(it) }

        /** How many branch outcomes [methodName] of [className] has probes for. */
        fun branchProbes(
            className: String,
            methodName: String,
        ): Int = of(className, methodName, ProbeKind.BRANCH).size

        /** How many branch outcomes of [methodName] of [className] were taken in all. */
        fun branchHits(
            className: String,
            methodName: String,
        ): Long = of(className, methodName, ProbeKind.BRANCH).sumOf { hitsOf(it) }
    }

    private fun cases(version: Int): List<Case> {
        val cases = mutableListOf<Case>()
        val plain = VersionedFixtures.plain(version)
        cases +=
            Case(
                "a class with a static initialiser and a branch",
                listOf(plain),
                { loader ->
                    val type = Class.forName(plain.name, true, loader)
                    val target = type.getDeclaredConstructor().newInstance()
                    listOf(
                        "counter=" + type.getField("counter").get(null),
                        "pick(5)=" + type.getMethod("pick", Int::class.java).invoke(target, 5),
                        "pick(-5)=" + type.getMethod("pick", Int::class.java).invoke(target, -5),
                        "pick(0)=" + type.getMethod("pick", Int::class.java).invoke(target, 0),
                    )
                },
            ) { counts ->
                assertEquals(1L, counts.entries(plain.name, "<clinit>"), "the static initialiser ran once")
                assertEquals(3L, counts.entries(plain.name, "pick"))
                assertEquals(2, counts.branchProbes(plain.name, "pick"), "both outcomes of the one branch")
                assertEquals(3L, counts.branchHits(plain.name, "pick"))
            }

        val straight = VersionedFixtures.straight(version)
        cases +=
            Case(
                "a class with neither a branch nor a static initialiser",
                listOf(straight),
                { loader -> listOf("seven=" + Class.forName(straight.name, true, loader).getMethod("seven").invoke(null)) },
            ) { counts -> assertEquals(1L, counts.entries(straight.name, "seven")) }

        val constants = VersionedFixtures.constants(version)
        cases +=
            Case(
                "an interface with a static field its initialiser sets",
                listOf(constants),
                { loader -> listOf("LIMIT=" + Class.forName(constants.name, true, loader).getField("LIMIT").get(null)) },
            ) { counts -> assertEquals(1L, counts.entries(constants.name, "<clinit>"), "the interface's static initialiser ran once") }

        if (version >= 52) {
            val (defaults, implementation) = VersionedFixtures.defaults(version)
            cases +=
                Case(
                    "an interface with a default and a static method",
                    listOf(defaults, implementation),
                    { loader ->
                        val iface = Class.forName(defaults.name, true, loader)
                        val impl = Class.forName(implementation.name, true, loader).getDeclaredConstructor().newInstance()
                        val twice = iface.getMethod("twice", Int::class.java)
                        listOf(
                            "twice(4)=" + twice.invoke(impl, 4),
                            "twice(-4)=" + twice.invoke(impl, -4),
                            "zero=" + iface.getMethod("zero").invoke(null),
                        )
                    },
                ) { counts ->
                    assertEquals(2L, counts.entries(defaults.name, "twice"))
                    assertEquals(1L, counts.entries(defaults.name, "zero"))
                    assertEquals(2, counts.branchProbes(defaults.name, "twice"))
                    assertEquals(2L, counts.branchHits(defaults.name, "twice"))
                }
        }

        if (version >= 49) {
            val marker = VersionedFixtures.annotationType(version)
            val carrier = VersionedFixtures.annotated(version, marker)
            cases +=
                Case(
                    "an annotation type with a static initialiser, read from a class it annotates",
                    listOf(marker, carrier),
                    { loader ->
                        val markerType = Class.forName(marker.name, true, loader)

                        @Suppress("UNCHECKED_CAST")
                        val annotation = Class.forName(carrier.name, true, loader).getAnnotation(markerType as Class<Annotation>)
                        listOf(
                            "LEVEL=" + markerType.getField("LEVEL").get(null),
                            "level=" + markerType.getMethod("level").invoke(annotation),
                            "toString=" + annotation.toString().substringBefore('('),
                            "hashCode=" + annotation.hashCode(),
                            "equals=" + (annotation == annotation),
                        )
                    },
                ) { counts -> assertEquals(1L, counts.entries(marker.name, "<clinit>")) }
        }

        if (version < 51) {
            val subroutine = VersionedFixtures.subroutine(version)
            cases +=
                Case(
                    "a method calling a subroutine that holds a branch",
                    listOf(subroutine),
                    { loader ->
                        val type = Class.forName(subroutine.name, true, loader)
                        val f = type.getMethod("f", Int::class.java)
                        listOf(
                            "f(5)=" + f.invoke(null, 5),
                            "f(0)=" + f.invoke(null, 0),
                            "f(-1)=" + f.invoke(null, -1),
                            "hits=" + type.getField("hits").get(null),
                        )
                    },
                ) { counts ->
                    assertEquals(3L, counts.entries(subroutine.name, "f"))
                    assertEquals(
                        6,
                        counts.branchProbes(subroutine.name, "f"),
                        "three conditionals, the one in the subroutine copied for each caller",
                    )
                    assertEquals(
                        6L,
                        counts.branchHits(subroutine.name, "f"),
                        "two conditionals taken in each of three calls, one of them inside a copy",
                    )
                }
        }

        if (version <= 48) {
            val annotated = VersionedFixtures.annotated(version)
            cases +=
                Case(
                    "a class carrying a RuntimeVisibleAnnotations attribute",
                    listOf(annotated),
                    { loader ->
                        val type = Class.forName(annotated.name, true, loader)
                        val target = type.getDeclaredConstructor().newInstance()
                        listOf("pick(2)=" + type.getMethod("pick", Int::class.java).invoke(target, 2))
                    },
                ) { counts ->
                    assertEquals(1L, counts.entries(annotated.name, "pick"))
                    assertEquals(1L, counts.branchHits(annotated.name, "pick"))
                }
        }
        return cases
    }

    @Test
    fun `generated classes at every class-file version define, initialise, run and count woven as they do unwoven`() {
        val started = System.nanoTime()
        val supported = Runtime.version().feature() + CLASS_VERSION_OFFSET
        val skipped = VersionedFixtures.VERSIONS.filter { it > supported }
        val failures = mutableListOf<String>()
        var covered = 0
        val perVersion = sortedMapOf<Int, Int>()
        for (version in VersionedFixtures.VERSIONS - skipped.toSet()) {
            for (case in cases(version)) {
                covered++
                perVersion.merge(version, 1, Int::plus)
                failures += runCase(version, case)
            }
        }
        println(
            "woven-class verification, matrix: fixtures per version $perVersion, skipped versions $skipped on JDK " +
                "${Runtime.version().feature()}, $covered cases (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms)",
        )
        assertTrue(failures.isEmpty(), "woven classes that differ from their unwoven twins:\n  ${failures.joinToString("\n  ")}")
        assertTrue(covered > 0)
    }

    /** What went wrong running [case] woven against its unwoven twin at [version], or nothing. */
    private fun runCase(
        version: Int,
        case: Case,
    ): List<String> {
        val label = "v$version ${case.label}"
        val originals = case.classes.associate { it.internalName to it.bytes }
        val registry = ProbeRegistry()
        val transformer = HotPathWeaver.offlineTransformer(listOf(VersionedFixtures.PACKAGE), registry)
        val parent = WovenClassVerificationTest::class.java.classLoader
        val wovenLoader = MatrixLoader(parent, originals, mutableMapOf())
        val twinLoader = MatrixLoader(parent, originals, originals.toMutableMap())
        for (fixture in case.classes) {
            val bytes =
                try {
                    transformer.transform(wovenLoader, fixture.internalName, null, null, fixture.bytes)
                } catch (t: Throwable) {
                    return listOf("$label: weaving ${fixture.name} failed: $t")
                } ?: return listOf("$label: ${fixture.name} was not woven")
            wovenLoader.definitions[fixture.internalName] = bytes
        }
        val twin = attempt { case.drive(twinLoader) }
        val woven = attempt { case.drive(wovenLoader) }
        if (!woven.ok && twin.ok) return listOf("$label: woven fails with ${woven.error}; unwoven runs and gives ${twin.result}")
        if (!woven.ok) {
            return listOf(
                "$label: both fail (woven: ${woven.error}; unwoven: ${twin.error}), so nothing here shows the weave is sound",
            )
        }
        if (woven.result != twin.result) return listOf("$label: woven gives ${woven.result}; unwoven gives ${twin.result}")
        return try {
            case.expect(countsOf(registry))
            emptyList()
        } catch (e: AssertionError) {
            listOf("$label: ${e.message}")
        }
    }

    private fun countsOf(registry: ProbeRegistry): Counts {
        val probes = registry.manifest(resource).probes
        val deltas = registry.computeDeltaBatch(resource).batch.deltas
        return Counts(probes, deltas.associate { (it.classId to it.probeIndex) to it.hitsTotal })
    }

    private companion object {
        const val UNRESOLVED = "Cannot resolve type description"

        /** Per corpus, the fewest classes that must weave and the fewest that must run in both forms. */
        val FLOORS =
            mapOf(
                "demo" to (78 to 77),
                "demo-spring" to (9 to 9),
                "scala" to (300 to 300),
                "spring-webmvc" to (430 to 390),
                "ktor-server-core" to (405 to 395),
            )
        const val MESSAGE_WIDTH = 160
        const val NANOS_PER_MILLI = 1_000_000
        const val CLASS_VERSION_OFFSET = 44
        const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."
        val CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")
    }
}
