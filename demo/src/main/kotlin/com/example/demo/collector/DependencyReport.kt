package com.example.demo.collector

import dev.otherlode.proto.DependencyDiscoverySource

internal data class DependencyIdentityView(
    val groupId: String,
    val artifactId: String,
    val version: String,
) {
    // Must escape `\`, `:` and `,` with a backslash as the server does.
    val key: String get() = "${escape(groupId)}:${escape(artifactId)}"

    private fun escape(id: String) = id.replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,")
}

internal data class DependencyView(
    val dependencyId: Int,
    val identities: List<DependencyIdentityView>,
    val discoverySource: DependencyDiscoverySource,
    val classCount: Int?,
    val location: String,
) {
    val identityKey: String get() =
        identities
            .map { it.key }
            .distinct()
            .sorted()
            .joinToString(",")
}

internal data class ExternalClassView(
    val dependencyId: Int?,
    val absent: Boolean,
)

internal enum class ReferenceOrigin {
    MANIFEST_METHOD,

    MANIFEST_CLASS,

    BASELINE,
}

internal data class HeldReferences(
    val className: String,
    val methodName: String?,
    val methodDescriptor: String?,
    val origin: ReferenceOrigin,
    val referencedClasses: List<String>,
    // An inline method's own references never count, since its Kotlin callers carry copies of them.
    val inline: Boolean = false,
    val hits: Long = 0L,
)

internal data class InstanceDependencyView(
    val instanceId: String,
    val referencesRecorded: Boolean,
    val baselineComplete: Boolean,
    val dependenciesListed: Boolean = false,
    val dependencies: List<DependencyView> = emptyList(),
    val loadedClassesTotal: Map<Int, Long> = emptyMap(),
    val externalClasses: Map<String, ExternalClassView> = emptyMap(),
    val references: List<HeldReferences> = emptyList(),
    val loadedClassNames: Set<String> = emptySet(),
    val zeroCountsAreEvidence: Boolean = true,
)

internal enum class DependencyStatus(
    val label: String,
) {
    UNLOADED("UNLOADED"),
    UNREFERENCED("UNREFERENCED"),
    UNREACHED("UNREACHED"),
    NO_LIVE_REFERENCE("NO LIVE REFERENCE"),

    FAILED_TO_LOAD("FAILED TO LOAD"),

    FAILED_TO_INITIALIZE("FAILED TO INITIALISE"),

    NOT_JUDGED("NOT JUDGED"),
    USED("USED"),

    LOADED("LOADED"),

    RESOURCES_ONLY("RESOURCES ONLY"),
}

internal data class ReferenceSite(
    val className: String,
    val methodName: String?,
    val neverLoaded: Boolean,
    val failedToLoad: Boolean = false,
    val failedToInitialize: Boolean = false,
) {
    override fun toString(): String {
        val where = methodName?.let { "$className#$it" } ?: "$className (class level)"
        return when {
            failedToLoad -> "$where (failed to load)"
            failedToInitialize -> "$where (failed to initialise)"
            neverLoaded -> "$where (never loaded)"
            else -> where
        }
    }
}

internal data class DependencyFinding(
    val identityKey: String,
    val status: DependencyStatus,
    val versionsByIdentity: Map<String, Set<String>>,
    val loadedClassesTotal: Long,
    val classCount: Int?,
    val discoverySources: Set<DependencyDiscoverySource>,
    val sites: List<ReferenceSite>,
)

internal data class AbsentReference(
    val className: String,
    val sites: List<ReferenceSite>,
)

internal data class DependencyReport(
    val findings: List<DependencyFinding>,
    val absentReferences: List<AbsentReference>,
    val referencesUnavailable: Boolean,
    val unlistedInstances: List<String>,
)

internal fun computeDependencyReport(
    instances: List<InstanceDependencyView>,
    failedClassNames: Set<String> = emptySet(),
    failedToInitializeClassNames: Set<String> = emptySet(),
): DependencyReport {
    val failed = failedClassNames - instances.flatMapTo(mutableSetOf()) { it.loadedClassNames }
    val recording = instances.filter { it.referencesRecorded }
    val methodHits =
        instances
            .flatMap { it.references }
            .filter { it.origin == ReferenceOrigin.MANIFEST_METHOD }
            .groupBy { Triple(it.className, it.methodName, it.methodDescriptor) }
            .mapValues { (_, held) -> held.sumOf { it.hits } }

    val referencesByDependency = mutableMapOf<String, MutableSet<Pair<ReferenceSite, Boolean>>>()
    val unjudgedDependencies = mutableSetOf<String>()
    val absentSites = mutableMapOf<String, MutableSet<ReferenceSite>>()
    for (instance in recording) {
        val identityKeys = instance.dependencies.associate { it.dependencyId to it.identityKey }
        for (held in instance.references) {
            if (held.inline) continue
            val live =
                when (held.origin) {
                    ReferenceOrigin.MANIFEST_METHOD -> {
                        (methodHits[Triple(held.className, held.methodName, held.methodDescriptor)] ?: 0L) >
                            0L
                    }

                    ReferenceOrigin.MANIFEST_CLASS -> {
                        held.className in instance.loadedClassNames && held.className !in failedToInitializeClassNames
                    }

                    ReferenceOrigin.BASELINE -> {
                        false
                    }
                }
            val failedSite = held.origin == ReferenceOrigin.BASELINE && instance.baselineComplete && held.className in failed
            val site =
                ReferenceSite(
                    held.className,
                    held.methodName,
                    neverLoaded = held.origin == ReferenceOrigin.BASELINE && !failedSite && held.className !in instance.loadedClassNames,
                    failedToLoad = failedSite,
                    failedToInitialize = held.origin != ReferenceOrigin.BASELINE && held.className in failedToInitializeClassNames,
                )
            for (referenced in held.referencedClasses) {
                val mapping = instance.externalClasses[referenced] ?: continue
                if (mapping.absent) {
                    absentSites.getOrPut(referenced) { mutableSetOf() } += site
                    continue
                }
                val identityKey = mapping.dependencyId?.let(identityKeys::get) ?: continue
                referencesByDependency.getOrPut(identityKey) { mutableSetOf() } += site to live
                if (!live && !instance.zeroCountsAreEvidence) unjudgedDependencies += identityKey
            }
        }
    }

    val findings =
        instances
            .flatMap { instance -> instance.dependencies.map { instance to it } }
            .groupBy { (_, dependency) -> dependency.identityKey }
            .map { (identityKey, listings) ->
                val loaded = listings.maxOf { (instance, dependency) -> instance.loadedClassesTotal[dependency.dependencyId] ?: 0L }
                val references = referencesByDependency[identityKey].orEmpty()
                val judging = listings.map { (instance, _) -> instance }.filter { it.referencesRecorded }
                val status =
                    when {
                        loaded == 0L && listings.all { (_, dependency) -> dependency.classCount == 0 } -> {
                            DependencyStatus.RESOURCES_ONLY
                        }

                        loaded == 0L &&
                            listings.any { (_, dependency) ->
                                dependency.discoverySource == DependencyDiscoverySource.STARTUP_CLASSPATH
                            } -> {
                            DependencyStatus.UNLOADED
                        }

                        judging.isEmpty() -> {
                            DependencyStatus.LOADED
                        }

                        references.any { (_, live) -> live } -> {
                            DependencyStatus.USED
                        }

                        identityKey in unjudgedDependencies -> {
                            DependencyStatus.NOT_JUDGED
                        }

                        references.any { (site, _) -> site.failedToLoad } -> {
                            DependencyStatus.FAILED_TO_LOAD
                        }

                        references.any { (site, _) -> site.failedToInitialize } -> {
                            DependencyStatus.FAILED_TO_INITIALIZE
                        }

                        !judging.all { it.baselineComplete } -> {
                            DependencyStatus.NO_LIVE_REFERENCE
                        }

                        references.isEmpty() -> {
                            DependencyStatus.UNREFERENCED
                        }

                        else -> {
                            DependencyStatus.UNREACHED
                        }
                    }
                DependencyFinding(
                    identityKey = identityKey,
                    status = status,
                    versionsByIdentity =
                        listings
                            .flatMap { (_, dependency) -> dependency.identities }
                            .groupBy({ it.key }, { it.version })
                            .mapValues { (_, versions) -> versions.filter { it.isNotEmpty() }.toSortedSet() },
                    loadedClassesTotal = loaded,
                    classCount = listings.mapNotNull { (_, dependency) -> dependency.classCount }.maxOrNull(),
                    discoverySources = listings.map { (_, dependency) -> dependency.discoverySource }.toSortedSet(),
                    sites = references.map { (site, _) -> site }.distinct().sortedBy { it.toString() },
                )
            }.sortedWith(compareBy({ it.status.ordinal }, { it.identityKey }))

    return DependencyReport(
        findings = findings,
        absentReferences =
            absentSites.entries
                .map { (className, sites) -> AbsentReference(className, sites.sortedBy { it.toString() }) }
                .sortedBy { it.className },
        referencesUnavailable = recording.isEmpty(),
        unlistedInstances =
            instances
                .filterNot { it.dependenciesListed }
                .map { it.instanceId }
                .distinct()
                .sorted(),
    )
}

internal fun formatDependencyReport(report: DependencyReport): List<String> {
    val lines = mutableListOf<String>()
    lines += ""
    lines += "=== otherlode demo: dependency report ==="
    val counts = report.findings.groupingBy { it.status }.eachCount()
    val judged = DependencyStatus.entries.filter { it != DependencyStatus.LOADED && it != DependencyStatus.RESOURCES_ONLY }
    var countsLine = judged.joinToString(", ") { "${it.label.lowercase()}: ${counts[it] ?: 0}" }
    if (report.referencesUnavailable || DependencyStatus.LOADED in counts) {
        countsLine += ", loaded: ${counts[DependencyStatus.LOADED] ?: 0}"
    }
    if (DependencyStatus.RESOURCES_ONLY in counts) {
        countsLine += ", resources only: ${counts.getValue(DependencyStatus.RESOURCES_ONLY)}"
    }
    lines += countsLine
    if (report.unlistedInstances.isNotEmpty()) {
        lines += "the dependency listing had not fully arrived from ${report.unlistedInstances.joinToString(", ")} " +
            "(no dependencies_listed), so an empty or short list may be missing entries"
    }
    if (report.referencesUnavailable) {
        lines += "no instance sent references_recorded (only one with include rules set records references), " +
            "so levels 2 and 3 (unreferenced, unreached) are unavailable"
    }
    for (finding in report.findings) {
        val versions =
            finding.versionsByIdentity.entries
                .singleOrNull()
                ?.value
                ?.joinToString(", ")
                ?: finding.versionsByIdentity.entries.joinToString(", ") { (key, versions) ->
                    if (versions.isEmpty()) key else "$key ${versions.joinToString(", ")}"
                }
        val versionLabel = versions.ifEmpty { "version unknown" }
        val classCount = finding.classCount?.toString() ?: "?"
        val sources = finding.discoverySources.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
        lines +=
            "  ${finding.status.label}: ${finding.identityKey} [$versionLabel] loaded ${finding.loadedClassesTotal} of $classCount classes ($sources)"
        if (finding.status in
            setOf(
                DependencyStatus.UNREACHED,
                DependencyStatus.NO_LIVE_REFERENCE,
                DependencyStatus.FAILED_TO_LOAD,
                DependencyStatus.FAILED_TO_INITIALIZE,
            )
        ) {
            finding.sites.forEach { lines += "    referenced from $it" }
        }
    }
    if (report.absentReferences.isNotEmpty()) {
        lines += "ABSENT REFERENCES: ${report.absentReferences.size}"
        for (absent in report.absentReferences) {
            lines += "  ABSENT: ${absent.className} (referenced from ${absent.sites.joinToString(", ")})"
        }
    }
    lines += "======================================"
    return lines
}
