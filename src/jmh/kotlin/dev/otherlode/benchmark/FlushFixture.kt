package dev.otherlode.benchmark

import dev.otherlode.export.BodyKind
import dev.otherlode.export.ClassListing
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

class PopulatedRegistry(
    val registry: ProbeRegistry,
    val arrays: List<LongArray>,
    private val changedArrays: Array<LongArray>,
    private val changedIndexes: IntArray,
) {
    val changedProbeCount: Int get() = changedIndexes.size

    fun markChanged() {
        for (i in changedIndexes.indices) changedArrays[i][changedIndexes[i]]++
    }
}

class FlushFixture private constructor(
    private val registrations: List<Registration>,
    val corpora: List<String>,
    val includePackages: List<String>,
) {
    val classCount: Int get() = registrations.size

    val probeCount: Int = registrations.sumOf { it.probes.size }

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
        const val CHANGED_EVERY = 10

        val ALL_CORPORA = listOf("demo", "demo-spring", "scala", "spring-webmvc", "ktor-server-core")

        const val CLASSPATH_PROPERTY = "otherlode.codesize.classpath."

        private val cache = HashMap<String, Pair<List<Registration>, List<String>>>()

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
                    }
                }
            }
            return recorder.registrations.toList() to corpus.includePackages
        }
    }
}

class EncodingExporter : Exporter {
    private val bytes = AtomicLong()
    private val manifestProbes = AtomicLong()
    private val deltaProbes = AtomicLong()
    private val deltaBatches = AtomicLong()

    val encodedBytes: Long get() = bytes.get()

    val manifestProbeCount: Long get() = manifestProbes.get()

    val deltaProbeCount: Long get() = deltaProbes.get()

    val deltaBatchCount: Long get() = deltaBatches.get()

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

    override fun exportClassListing(listing: ClassListing) {
        bytes.addAndGet(ProtoPayloadCodec.encode(listing).size.toLong())
    }
}

internal val FLUSH_RESOURCE = ResourceAttributes("flush-benchmark", null, "instance-1", null, "run-1")
