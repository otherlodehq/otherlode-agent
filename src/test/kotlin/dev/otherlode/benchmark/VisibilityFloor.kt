package dev.otherlode.benchmark

import dev.otherlode.export.BranchRole
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ProbeManifest
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.RoutineKind
import dev.otherlode.export.UnreadShape
import dev.otherlode.registry.ProbeRegistry
import java.io.File
import java.net.URLClassLoader

/** Which way a visibility metric may move without the gate failing. */
internal enum class Direction {
    /** More is more visibility, so a fall below the baseline fails. */
    FLOOR,

    /** More is less visibility, so a rise above the baseline fails. */
    CEILING,
}

/** The outcome of comparing a measurement with a baseline. */
internal class Comparison(
    val failures: List<String>,
    val notes: List<String>,
)

/**
 * Counts what the agent identifies when it weaves the benchmark corpora, and compares the counts
 * with a committed baseline. A metric is named `<corpus>.<metric>`. Each metric is a floor or a
 * ceiling, as [directionOf] says.
 */
internal object VisibilityFloor {
    /** The Gradle-set system property that holds the path of the baseline source file in update mode. */
    const val UPDATE_PROPERTY = "otherlode.visibility.baselineSource"

    /** The classpath resource that holds the committed baseline. */
    const val BASELINE_RESOURCE = "/visibility-baseline.txt"

    /** The command that rewrites the baseline, named in the file header and in a failure. */
    const val REGENERATE_COMMAND = "./gradlew :test --tests dev.otherlode.benchmark.VisibilityFloorTest -PupdateVisibilityBaseline"

    private val HEADER =
        listOf(
            "# What the agent identifies in each benchmark corpus, one `corpus.metric=value` line per metric.",
            "# VisibilityFloorTest fails when a floor metric falls or a ceiling metric rises.",
            "# Regenerate with: $REGENERATE_COMMAND",
        )

    private val CEILING_METRICS = setOf("classes.skipped", "classes.thrown")

    /**
     * The direction of [metric], named without its corpus. A ceiling counts a loss: a class the
     * agent refused or an unread shape. Every other metric is a floor. This is the only place that
     * decides.
     */
    fun directionOf(metric: String): Direction =
        if (metric in CEILING_METRICS || ".unread_shape." in metric) Direction.CEILING else Direction.FLOOR

    private fun directionOfKey(key: String): Direction = directionOf(key.substringAfter('.'))

    /** Weaves every class of [corpusName] and counts what the registries built, keyed `<corpus>.<metric>`. */
    fun measure(corpusName: String): Map<String, Long> {
        val corpus = BenchmarkCorpus.load(corpusName)
        val manifests = mutableListOf<ProbeManifest>()
        var woven = 0L
        var thrown = 0L
        val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")
        for (set in corpus.classSets) {
            val urls = CorpusWeaving.classSetUrls(corpusName, set)
            URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val registry = ProbeRegistry()
                val transformer = HotPathWeaver.offlineTransformer(corpus.includePackages, registry)
                for ((internalName, bytes) in set.classes) {
                    try {
                        if (transformer.transform(loader, internalName, null, null, bytes) != null) woven++
                    } catch (t: Throwable) {
                        thrown++
                    }
                }
                manifests += registry.manifest(resource)
            }
        }
        return count(corpusName, manifests, woven, thrown)
    }

    /** Counts [manifests], one per class set of [corpus], with [woven] classes woven and [thrown] refused by an exception. */
    fun count(
        corpus: String,
        manifests: List<ProbeManifest>,
        woven: Long,
        thrown: Long,
    ): Map<String, Long> {
        val metrics = sortedMapOf<String, Long>()

        fun put(
            metric: String,
            value: Long,
        ) {
            metrics["$corpus.$metric"] = value
        }

        val probes = manifests.flatMap { it.probes }
        val methods = probes.filter { it.kind == ProbeKind.METHOD }
        val sites = methods.flatMap { it.branchSites }
        val locations = manifests.flatMap { it.classLocations }
        val edges = methods.flatMap { it.calls }

        put("classes.woven", woven)
        put("classes.skipped", manifests.sumOf { it.skippedClasses.size }.toLong())
        put("classes.thrown", thrown)
        for (kind in ProbeKind.entries) put("probes.$kind", probes.count { it.kind == kind }.toLong())
        put("branch.sites", sites.size.toLong())
        for (role in BranchRole.entries) {
            put("branch.outcomes.$role", sites.sumOf { site -> site.outcomes.count { it.role == role } }.toLong())
        }
        put(
            "branch.outcomes.routine",
            sites
                .sumOf { site ->
                    site.outcomes.count { it.routine != RoutineKind.NONE }
                }.toLong(),
        )
        for (shape in UnreadShape.entries.filter { it != UnreadShape.NONE }) {
            put("branch.outcomes.unread_shape.$shape", sites.sumOf { site -> site.outcomes.count { it.unreadShape == shape } }.toLong())
        }
        for (kind in CallEdgeKind.entries) put("edges.$kind", edges.count { it.kind == kind }.toLong())
        for ((value, group) in methods.groupBy { it.generatedBy }.filterKeys { it != GeneratedBy.NONE }) {
            put("methods.generated_by.$value", group.size.toLong())
        }
        for (shape in UnreadShape.entries.filter { it != UnreadShape.NONE }) {
            put("methods.unread_shape.$shape", methods.count { it.unreadShape == shape }.toLong())
        }
        put("methods.inline", methods.count { it.inline }.toLong())
        put("methods.lambda_body", methods.count { it.lambdaBody }.toLong())
        put("methods.static", methods.count { it.static }.toLong())
        put("methods.with_parameter_names", methods.count { it.parameterNames.isNotEmpty() }.toLong())
        put("methods.with_generic_signature", methods.count { it.genericSignature.isNotEmpty() }.toLong())
        put("class_locations", locations.size.toLong())
        put("class_locations.with_source_file", locations.count { !it.sourceFile.isNullOrEmpty() }.toLong())
        for ((value, group) in locations.groupBy { it.bodyKind }) put("class_locations.body_kind.$value", group.size.toLong())
        for ((value, group) in locations.groupBy { it.kotlinKind }) put("class_locations.kotlin_kind.$value", group.size.toLong())
        put("references.from_methods", methods.sumOf { it.referencedClasses.size }.toLong())
        put("references.from_classes", manifests.sumOf { m -> m.classReferences.sumOf { it.referencedClasses.size } }.toLong())
        return metrics
    }

    /**
     * Compares [measured] with [baseline]. A floor that fell or a ceiling that rose fails. A metric
     * in the baseline but absent from the measurement counts as zero, and so does a ceiling the
     * baseline lacks, so a ceiling above zero fails until the baseline names it. Any other metric
     * the baseline lacks, and a floor that rose or a ceiling that fell, add a note and pass.
     */
    fun compare(
        baseline: Map<String, Long>,
        measured: Map<String, Long>,
    ): Comparison {
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (key in (baseline.keys + measured.keys).toSortedSet()) {
            val old = baseline[key]
            val new = measured[key] ?: 0L
            val direction = directionOfKey(key)
            if (old == null && !(direction == Direction.CEILING && new > 0)) {
                notes += "$key is not in the baseline (measured $new)"
                continue
            }
            val baselineValue = old ?: 0L
            val worse = if (direction == Direction.FLOOR) new < baselineValue else new > baselineValue
            val better = if (direction == Direction.FLOOR) new > baselineValue else new < baselineValue
            when {
                worse -> failures += "$key (${direction.name.lowercase()}): baseline $baselineValue, measured $new"
                better -> notes += "$key moved from $baselineValue to $new in the good direction (${direction.name.lowercase()})"
            }
        }
        return Comparison(failures, notes)
    }

    /** Reads `corpus.metric=value` lines, skipping blank lines and `#` comments. */
    fun parse(text: String): Map<String, Long> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (key, value) = line.split('=', limit = 2).also { require(it.size == 2) { "not a metric line: $line" } }
                key to value.trim().toLong()
            }

    /** The baseline file text for [measured]: the header, then the metrics sorted by name. */
    fun render(measured: Map<String, Long>): String =
        (HEADER + measured.toSortedMap().map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")

    /** Writes [measured] to [file] in baseline format. */
    fun write(
        file: File,
        measured: Map<String, Long>,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(render(measured))
    }

    /** Reads the committed baseline from the test classpath. */
    fun readBaseline(): Map<String, Long> {
        val stream =
            checkNotNull(VisibilityFloor::class.java.getResourceAsStream(BASELINE_RESOURCE)) {
                "no $BASELINE_RESOURCE on the classpath; run $REGENERATE_COMMAND"
            }
        return parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }
}
