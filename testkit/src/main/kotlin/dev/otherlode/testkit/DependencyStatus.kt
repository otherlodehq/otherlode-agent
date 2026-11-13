package dev.otherlode.testkit

/**
 * The status [OtherlodeTestCollector.dependency] gives one dependency. A dependency reads [RESOURCES_ONLY] or
 * [UNLOADED] before any status that rests on references. A minor release can add values, so a `when` over
 * this enum needs an `else` branch.
 */
public enum class DependencyUsage {
    /** Listed from a startup classpath, and no class from it loaded, unless it reads [RESOURCES_ONLY]. */
    UNLOADED,

    /** Nothing in the adopter's code references it, per complete static baselines. */
    UNREFERENCED,

    /** Referenced, but from no live site, per complete static baselines. */
    UNREACHED,

    /**
     * No live site references it, and an instance that listed it and records references sent no complete
     * static baseline, so [UNREFERENCED] and [UNREACHED] cannot be told apart.
     */
    NO_LIVE_REFERENCE,

    /** No live site references it, and a class that failed to load does, per a complete static baseline. */
    FAILED_TO_LOAD,

    /** Referenced from a live site, such as a method with hits. */
    USED,

    /** No instance that listed it records references, so no finer status applies. */
    LOADED,

    /**
     * Its listings count zero classes in it and no class from it loaded, as for a jar of web assets
     * or native libraries.
     */
    RESOURCES_ONLY,

    /**
     * No live site references it. A class that failed to initialise references it, and no class that
     * failed to load does.
     */
    FAILED_TO_INITIALIZE,

    /**
     * No live site references it, and a method with no hits in a class whose static initialiser has started
     * but not finished does, so its zero counts are no evidence.
     */
    WITHOUT_EVIDENCE,
}

/**
 * One `groupId:artifactId` a dependency carries, with the non-empty versions it was listed at, sorted.
 * [groupId] is empty for an identity read from a jar's file name.
 */
@ConsistentCopyVisibility
public data class DependencyIdentityRef internal constructor(
    val groupId: String,
    val artifactId: String,
    val versions: Set<String>,
)

/**
 * One place in the adopter's code that references a dependency or an absent class. [methodName] is
 * null for a class-level reference.
 *
 * - [neverLoaded] marks a site from a static baseline in a class that its instance did not load. It
 *   is false when [failedToLoad] is true.
 * - [failedToLoad] marks a site from a complete static baseline in a class that some instance failed
 *   to load and none loaded.
 * - [failedToInitialize] marks a site that is not live, in a class that failed to initialise.
 */
@ConsistentCopyVisibility
public data class DependencyReferenceSite internal constructor(
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

/**
 * One dependency as [OtherlodeTestCollector.dependency] reports it, merged across the instances that
 * listed it under the same [identityKey].
 *
 * - [identityKey] is its sorted `groupId:artifactId` pairs joined with commas. A backslash escapes a
 *   `\`, `:` or `,` inside an id. A file-name identity has an empty group, as in `:commons-lang3`.
 * - [identities] holds each pair with its versions. A shaded jar can carry several.
 * - [loadedClassesTotal] is the largest loaded-class count any one instance reported.
 * - [classCount] is the largest class count a listing gave, or null when none gave one.
 * - [discoverySources] holds the sources its listings came from.
 * - [sites] holds the sites that reference it, live or not, sorted by their string form.
 */
@ConsistentCopyVisibility
public data class DependencyStatus internal constructor(
    val identityKey: String,
    val status: DependencyUsage,
    val identities: List<DependencyIdentityRef>,
    val loadedClassesTotal: Long,
    val classCount: Int?,
    val discoverySources: Set<DependencyDiscoverySource>,
    val sites: List<DependencyReferenceSite>,
)

/**
 * A referenced class recorded as absent, with the sites that reference it, sorted by their string form.
 * It belongs to no dependency.
 */
@ConsistentCopyVisibility
public data class AbsentReference internal constructor(
    val className: String,
    val sites: List<DependencyReferenceSite>,
)

/**
 * Thrown by [OtherlodeTestCollector.dependency] for a `groupId:artifactId` that no manifest has
 * listed. The message names the dependencies this collector knows, or says that it knows none.
 */
public class UnknownDependencyException internal constructor(
    message: String,
) : RuntimeException(message)
