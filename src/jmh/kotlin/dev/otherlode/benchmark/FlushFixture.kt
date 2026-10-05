package dev.otherlode.benchmark

import dev.otherlode.export.BodyKind
import dev.otherlode.export.DeltaBatch
import dev.otherlode.export.Exporter
import dev.otherlode.export.KotlinKind
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ProtoPayloadCodec
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.StaticBaseline
import dev.otherlode.registry.ProbeMeta
import dev.otherlode.registry.ProbeRegistry
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicLong

/**
 * One `ProbeRegistry.register` call the real transformer made while weaving a corpus class, kept
 * so the same registration can be replayed into any number of fresh registries without weaving
 * again.
 */
class Registration(
    val className: String,
    val layoutHash: Long,
    val probes: List<ProbeMeta>,
    val classLoader: ClassLoader?,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val classReferences: List<String>,
    val sourceFile: String?,
    val bodyKind: BodyKind,
    val sourceName: String?,
    val kotlinKind: KotlinKind,
)

/** A registry that keeps every registration the transformer makes, in order, besides doing it. */
private class RecordingRegistry : ProbeRegistry(confirmsDefinitions = true) {
    val registrations = mutableListOf<Registration>()

    override fun register(
        className: String,
        layoutHash: Long,
        probes: List<ProbeMeta>,
        classLoader: ClassLoader?,
        superClassName: String?,
        interfaceNames: List<String>,
        classReferences: List<String>,
        sourceFile: String?,
        bodyKind: BodyKind,
        sourceName: String?,
        kotlinKind: KotlinKind,
    ): LongArray {
        registrations +=
            Registration(
                className,
                layoutHash,
                probes,
                classLoader,
                superClassName,
                interfaceNames,
                classReferences,
                sourceFile,
                bodyKind,
                sourceName,
                kotlinKind,
            )
        return super.register(
            className,
            layoutHash,
            probes,
            classLoader,
            superClassName,
            interfaceNames,
            classReferences,
            sourceFile,
            bodyKind,
            sourceName,
            kotlinKind,
        )
    }
}

/**
 * A [ProbeRegistry] filled from a [FlushFixture], with the count array of every registered class.
 * Every class is confirmed defined, the state a loaded class reaches once the loaded-class sweep
 * has seen it, so the manifest and the delta batches publish all of it.
 */
class PopulatedRegistry(
    val registry: ProbeRegistry,
    val arrays: List<LongArray>,
    /** The slots [FlushFixture.CHANGED_EVERY] selects, as parallel arrays: the class's counts and the probe index in it. */
    private val changedArrays: Array<LongArray>,
    private val changedIndexes: IntArray,
) {
    /** How many probes [markChanged] touches. */
    val changedProbeCount: Int get() = changedIndexes.size

    /** Adds one hit to every probe in the changed share. */
    fun markChanged() {
        for (i in changedIndexes.indices) changedArrays[i][changedIndexes[i]]++
    }
}

/**
 * The registrations of woven benchmark corpora, so a flush benchmark can fill a registry of a
 * realistic size without weaving each time. The corpora are woven offline through the real
 * transformer ([HotPathWeaver.offlineTransformer]), and every
 * class set resolves its dependencies through the classpath the build passes in the
 * `otherlode.codesize.classpath.<corpus>.<class set>` system properties. The classes are never
 * defined: a class is confirmed by naming it to [ProbeRegistry.confirmFrom], the call the
 * loaded-class sweep makes with the JVM's loaded names.
 */
class FlushFixture private constructor(
    private val registrations: List<Registration>,
    /** The corpora this fixture holds, by name. */
    val corpora: List<String>,
    /** The package prefixes that cover every class in [corpora]. */
    val includePackages: List<String>,
) {
    /** Classes the registry holds once [populate] has run. */
    val classCount: Int get() = registrations.size

    /** Probe slots across those classes. */
    val probeCount: Int = registrations.sumOf { it.probes.size }

    /** Registers every recorded class into a new registry, confirms all of them, and returns it. */
    fun populate(): PopulatedRegistry {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val arrays =
            registrations.map {
                registry.register(
                    it.className,
                    it.layoutHash,
                    it.probes,
                    it.classLoader,
                    it.superClassName,
                    it.interfaceNames,
                    it.classReferences,
                    it.sourceFile,
                    it.bodyKind,
                    it.sourceName,
                    it.kotlinKind,
                )
            }
        registry.confirmFrom(registry.registeredClassNames())
        val changedArrays = ArrayList<LongArray>()
        val changedIndexes = ArrayList<Int>()
        var slot = 0
        for (array in arrays) {
            for (index in array.indices) {
                if (slot++ % CHANGED_EVERY == 0) {
                    changedArrays += array
                    changedIndexes += index
                }
            }
        }
        return PopulatedRegistry(registry, arrays, changedArrays.toTypedArray(), changedIndexes.toIntArray())
    }

    companion object {
        /** One probe in this many counts as changed since the last delivered delta batch: 10%. */
        const val CHANGED_EVERY = 10

        /** Every corpus the benchmark weaves, in the order the analyser benchmark lists them. */
        val ALL_CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")

        /** The system property prefix that names each class set's dependency classpath. */
        const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."

        private val cache = HashMap<String, Pair<List<Registration>, List<String>>>()

        /** The fixture for [corpora], weaving each corpus the first time any fixture asks for it. */
        @Synchronized
        fun of(vararg corpora: String): FlushFixture {
            val woven = corpora.map { name -> cache.getOrPut(name) { weave(name) } }
            return FlushFixture(
                woven.flatMap { it.first },
                corpora.toList(),
                woven.flatMap { it.second }.distinct().sorted(),
            )
        }

        private fun weave(corpusName: String): Pair<List<Registration>, List<String>> {
            val corpus = BenchmarkCorpus.load(corpusName)
            val recorder = RecordingRegistry()
            for (set in corpus.classSets) {
                val classpath = System.getProperty("$CLASSPATH_PROPERTY$corpusName.${set.name}")
                check(!classpath.isNullOrBlank()) { "no classpath for $corpusName.${set.name}; run the benchmark through Gradle" }
                val corpusPath = checkNotNull(System.getProperty("${BenchmarkCorpus.PROPERTY_PREFIX}$corpusName.${set.name}"))
                val urls =
                    (corpusPath.split(File.pathSeparator) + classpath.split(File.pathSeparator))
                        .filter { it.isNotEmpty() }
                        .map { File(it).toURI().toURL() }
                val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
                val transformer = HotPathWeaver.offlineTransformer(corpus.includePackages, recorder)
                for ((internalName, bytes) in set.classes) {
                    try {
                        transformer.transform(loader, internalName, null, null, bytes)
                    } catch (_: Throwable) {
                        // A class the agent refuses is skipped, as it is in a real process.
                    }
                }
            }
            return recorder.registrations.toList() to corpus.includePackages
        }
    }
}

/**
 * An [Exporter] that encodes every payload with [ProtoPayloadCodec], as the HTTP exporter does
 * before it posts, and then drops the bytes. It keeps what the last flush carried so a test can
 * check what an operation measured.
 */
class EncodingExporter : Exporter {
    private val bytes = AtomicLong()
    private val manifestProbes = AtomicLong()
    private val deltaProbes = AtomicLong()
    private val deltaBatches = AtomicLong()

    /** Encoded bytes since the last [reset], across every payload. */
    val encodedBytes: Long get() = bytes.get()

    /** Probe locations on the manifests since the last [reset]. */
    val manifestProbeCount: Long get() = manifestProbes.get()

    /** Probe deltas on the delta batches since the last [reset]. */
    val deltaProbeCount: Long get() = deltaProbes.get()

    /** Delta batches since the last [reset]; an empty heartbeat counts. */
    val deltaBatchCount: Long get() = deltaBatches.get()

    /** Zeroes every counter. */
    fun reset() {
        bytes.set(0)
        manifestProbes.set(0)
        deltaProbes.set(0)
        deltaBatches.set(0)
    }

    override fun exportDeltaBatch(batch: DeltaBatch) {
        bytes.addAndGet(ProtoPayloadCodec.encode(batch).size.toLong())
        deltaProbes.addAndGet(batch.deltas.size.toLong())
        deltaBatches.incrementAndGet()
    }

    override fun exportManifest(manifest: ProbeManifest) {
        bytes.addAndGet(ProtoPayloadCodec.encode(manifest).size.toLong())
        manifestProbes.addAndGet(manifest.probes.size.toLong())
    }

    override fun exportStaticBaseline(baseline: StaticBaseline) {
        bytes.addAndGet(ProtoPayloadCodec.encode(baseline).size.toLong())
    }
}

/** The resource every flush benchmark stamps on its payloads. */
internal val FLUSH_RESOURCE = ResourceAttributes("flush-benchmark", null, "instance-1", null, "run-1")
