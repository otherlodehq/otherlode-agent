package dev.otherlode.testkit

/**
 * What the collector's rules say about one dependency. Findings sort in this order. Values may be added in a minor release, so a `when` over this enum needs an `else` branch.
 */
public enum class DependencyUsage {
    /** Listed from an instance's startup classpath, and no instance loaded a class from it. */
    UNLOADED,

    /** A class from it loaded, and nothing in the adopter's code references it, per a complete baseline. */
    UNREFERENCED,

    /** Referenced only from methods never hit or classes never loaded, per a complete baseline. */
    UNREACHED,

    /**
     * Loaded, with no live reference, from an instance that sent no complete static baseline: it is
     * unreferenced or unreached, and without the baseline the two cannot be told apart.
     */
    NO_LIVE_REFERENCE,

    /**
     * Loaded, with no live reference, and referenced from a class that failed to load. A prompt to
     * review, not a claim that it can go. The class may fail because of the classpath this
     * dependency is on, and a dependency only a long-failing class uses may not be needed.
     */
    FAILED_TO_LOAD,

    /** Referenced from a method with hits, or at class level by a class that loaded. */
    USED,

    /** Loaded, and no instance listing it records references (its include rules are unset), so nothing further is claimed. */
    LOADED,

    /**
     * Every listing counted no class in it: native libraries, web assets, message bundles. Whether
     * a class loaded from it says nothing about whether it is used, so nothing is claimed.
     */
    RESOURCES_ONLY,
}

/**
 * One `groupId:artifactId` a dependency carries, with every non-empty version any instance listed
 * it at. [groupId] is empty for an identity read from the jar's filename, which carries no group.
 */
@ConsistentCopyVisibility
public data class DependencyIdentityRef internal constructor(
    val groupId: String,
    val artifactId: String,
    val versions: Set<String>,
)

/**
 * One place in the adopter's code that references a dependency: `Class#method`, or the class
 * itself for a class-level reference ([methodName] null). [neverLoaded] is true for a site the
 * static baseline declared in a class no manifest from that instance ever named. [failedToLoad] is
 * true for a baseline site in a class that some instance failed to load and none loaded.
 */
@ConsistentCopyVisibility
public data class DependencyReferenceSite internal constructor(
    val className: String,
    val methodName: String?,
    val neverLoaded: Boolean,
    val failedToLoad: Boolean = false,
) {
    override fun toString(): String {
        val where = methodName?.let { "$className#$it" } ?: "$className (class level)"
        return when {
            failedToLoad -> "$where (failed to load)"
            neverLoaded -> "$where (never loaded)"
            else -> where
        }
    }
}

/**
 * One dependency as [OtherlodeTestCollector.dependency] reports it, merged across every instance that
 * listed it by its sorted identity pairs, never by the per-instance id.
 *
 * [identityKey] is those pairs joined with commas, for example `com.fasterxml.jackson.core:jackson-databind`,
 * or `:commons-lang3` for a filename-derived identity. [identities] names each pair with its
 * versions; a shaded jar carries several. [loadedClassesTotal] is the largest distinct-class count
 * any one instance reported, [classCount] the jar's class count when the listing knew it.
 * [sites] lists every referencing site the rules found, sorted, live or not: for an
 * [DependencyUsage.UNREACHED] or [DependencyUsage.NO_LIVE_REFERENCE] dependency these are the dead
 * references, and for [DependencyUsage.UNREFERENCED] the list is empty.
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
 * A referenced class no loader could find, with every site that references it: code guarded by a
 * check for an optional library, for one. It maps to no dependency, so it is listed on its own.
 */
@ConsistentCopyVisibility
public data class AbsentReference internal constructor(
    val className: String,
    val sites: List<DependencyReferenceSite>,
)

/**
 * Thrown when [OtherlodeTestCollector.dependency] names a `groupId:artifactId` no manifest has listed,
 * instead of reading as unloaded or unreferenced. The message names every identity the collector
 * does know, or says that no instance has listed any dependency yet.
 */
public class UnknownDependencyException internal constructor(
    message: String,
) : RuntimeException(message)
