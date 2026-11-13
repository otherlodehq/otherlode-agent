package dev.otherlode.testkit

import dev.otherlode.export.DependencyDiscoverySource

internal data class DependencyIdentityView(
    val groupId: String,
    val artifactId: String,
    val version: String,
) {
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
    val inline: Boolean = false,
    val hits: Long = 0L,
)

internal data class InstanceDependencyView(
    val instanceId: String,
    val referencesRecorded: Boolean,
    val baselineComplete: Boolean,
    val dependencies: List<DependencyView> = emptyList(),
    val loadedClassesTotal: Map<Int, Long> = emptyMap(),
    val externalClasses: Map<String, ExternalClassView> = emptyMap(),
    val references: List<HeldReferences> = emptyList(),
    val loadedClassNames: Set<String> = emptySet(),
)

internal data class DependencyFinding(
    val identityKey: String,
    val status: DependencyUsage,
    val versionsByIdentity: Map<Pair<String, String>, Set<String>>,
    val identities: List<Pair<String, String>>,
    val loadedClassesTotal: Long,
    val classCount: Int?,
    val discoverySources: Set<DependencyDiscoverySource>,
    val sites: List<DependencyReferenceSite>,
    val listingInstances: Set<String> = emptySet(),
)

internal data class DependencyReport(
    val findings: List<DependencyFinding>,
    val absentReferences: List<AbsentReference>,
    val referencesUnavailable: Boolean,
)

internal fun computeDependencyReport(
    instances: List<InstanceDependencyView>,
    failedClassNames: Set<String> = emptySet(),
    failedToInitializeClassNames: Set<String> = emptySet(),
    skippedClassNames: Set<String> = emptySet(),
    initializerGaps: Map<String, Set<String>> = emptyMap(),
): DependencyReport {
    val loadedAnywhere = instances.flatMapTo(mutableSetOf()) { it.loadedClassNames }
    val failed = failedClassNames - loadedAnywhere
    val recording = instances.filter { it.referencesRecorded }
    val manifestMethods =
        instances
            .flatMap { instance -> instance.references.filter { it.origin == ReferenceOrigin.MANIFEST_METHOD }.map { instance to it } }
            .groupBy({ (_, held) -> Triple(held.className, held.methodName, held.methodDescriptor) }, { it })
    val methodHits = manifestMethods.mapValues { (_, held) -> held.sumOf { (_, reference) -> reference.hits } }

    fun noEvidenceAboutMethod(key: Triple<String, String?, String?>): Boolean {
        val knowing = manifestMethods[key]?.map { (instance, _) -> instance.instanceId }?.toSet().orEmpty()
        return knowing.isNotEmpty() && knowing.all { key.first in initializerGaps[it].orEmpty() }
    }

    val referencesByDependency = mutableMapOf<String, MutableSet<Pair<DependencyReferenceSite, Boolean>>>()
    val withoutEvidence = mutableSetOf<String>()
    val absentSites = mutableMapOf<String, MutableSet<DependencyReferenceSite>>()
    for (instance in recording) {
        val identityKeys = instance.dependencies.associate { it.dependencyId to it.identityKey }
        for (held in instance.references) {
            if (held.inline) continue
            val methodKey = Triple(held.className, held.methodName, held.methodDescriptor)
            val classLevel = held.methodName == null
            val live =
                when {
                    held.origin == ReferenceOrigin.MANIFEST_CLASS -> held.className in instance.loadedClassNames
                    classLevel -> held.className in loadedAnywhere
                    else -> (methodHits[methodKey] ?: 0L) > 0L
                }
            val failedSite = held.origin == ReferenceOrigin.BASELINE && held.className in failed
            val failedInitialization = held.className in failedToInitializeClassNames
            val liveSite = live && !(failedInitialization && classLevel)
            val unhitMethodWithoutEvidence =
                !live &&
                    !classLevel &&
                    (
                        noEvidenceAboutMethod(methodKey) || held.className in skippedClassNames
                    )
            val site =
                DependencyReferenceSite(
                    held.className,
                    held.methodName,
                    neverLoaded = held.origin == ReferenceOrigin.BASELINE && !failedSite && held.className !in loadedAnywhere,
                    failedToLoad = failedSite,
                    failedToInitialize = failedInitialization && !liveSite,
                )
            for (referenced in held.referencedClasses) {
                val mapping = instance.externalClasses[referenced] ?: continue
                if (mapping.absent) {
                    absentSites.getOrPut(referenced) { mutableSetOf() } += site
                    continue
                }
                val identityKey = mapping.dependencyId?.let(identityKeys::get) ?: continue
                referencesByDependency.getOrPut(identityKey) { mutableSetOf() } += site to liveSite
                if (unhitMethodWithoutEvidence) withoutEvidence += identityKey
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
                            DependencyUsage.RESOURCES_ONLY
                        }

                        loaded == 0L &&
                            listings.any { (_, dependency) ->
                                dependency.discoverySource == DependencyDiscoverySource.STARTUP_CLASSPATH
                            } -> {
                            DependencyUsage.UNLOADED
                        }

                        judging.isEmpty() -> {
                            DependencyUsage.LOADED
                        }

                        references.any { (_, live) -> live } -> {
                            DependencyUsage.USED
                        }

                        references.any { (site, _) -> site.failedToLoad } -> {
                            DependencyUsage.FAILED_TO_LOAD
                        }

                        references.any { (site, _) -> site.failedToInitialize } -> {
                            DependencyUsage.FAILED_TO_INITIALIZE
                        }

                        identityKey in withoutEvidence -> {
                            DependencyUsage.WITHOUT_EVIDENCE
                        }

                        !judging.all { it.baselineComplete } -> {
                            DependencyUsage.NO_LIVE_REFERENCE
                        }

                        references.isEmpty() -> {
                            DependencyUsage.UNREFERENCED
                        }

                        else -> {
                            DependencyUsage.UNREACHED
                        }
                    }
                val identities = listings.flatMap { (_, dependency) -> dependency.identities }
                DependencyFinding(
                    identityKey = identityKey,
                    status = status,
                    versionsByIdentity =
                        identities
                            .groupBy({ it.groupId to it.artifactId }, { it.version })
                            .mapValues { (_, versions) -> versions.filter { it.isNotEmpty() }.toSortedSet() },
                    identities =
                        identities
                            .map { it.groupId to it.artifactId }
                            .distinct()
                            .sortedBy { (groupId, artifactId) -> "$groupId:$artifactId" },
                    loadedClassesTotal = loaded,
                    classCount = listings.mapNotNull { (_, dependency) -> dependency.classCount }.maxOrNull(),
                    discoverySources = listings.map { (_, dependency) -> dependency.discoverySource }.toSortedSet(),
                    sites = references.map { (site, _) -> site }.distinct().sortedBy { it.toString() },
                    listingInstances = listings.map { (instance, _) -> instance.instanceId }.toSet(),
                )
            }.sortedWith(compareBy({ it.status.ordinal }, { it.identityKey }))

    return DependencyReport(
        findings = findings,
        absentReferences =
            absentSites.entries
                .map { (className, sites) -> AbsentReference(className, sites.sortedBy { it.toString() }) }
                .sortedBy { it.className },
        referencesUnavailable = recording.isEmpty(),
    )
}
