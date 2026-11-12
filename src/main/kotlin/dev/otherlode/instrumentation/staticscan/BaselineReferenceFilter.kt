package dev.otherlode.instrumentation.staticscan

import dev.otherlode.agentLogger
import dev.otherlode.dependencies.DependencyListingRun
import dev.otherlode.export.ExternalClass
import dev.otherlode.instrumentation.PlatformProvidedClasses
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.ExternalClassRegistry
import java.lang.System.Logger.Level
import java.time.Duration

/**
 * Filters a static scan's references without a defining loader. Drops names the platform provides,
 * names among the scan's own classes, and names the scan saw at the root of a jar when the startup
 * listing's class index does not map them. Keeps every other name, and records a kept name the class
 * index maps ([DependencyRegistry.dependencyForClass]) into [externalClasses] as that dependency's.
 *
 * The filtered result carries what [ExternalClassRegistry.mappingFor] returns for each kept name.
 * [filter] runs [dependencyListing] first if no one has, then waits up to [listingWait] for the
 * listing to end. If it failed or is still running, every reference list is emptied.
 */
class BaselineReferenceFilter(
    private val dependencies: DependencyRegistry,
    private val externalClasses: ExternalClassRegistry,
    private val listingWait: Duration = DEFAULT_LISTING_WAIT,
    private val providedByPlatform: (String) -> Boolean = PlatformProvidedClasses()::provides,
    private val dependencyListing: DependencyListingRun = DependencyListingRun {},
) {
    private val log by agentLogger(BaselineReferenceFilter::class.java.name)

    /**
     * [result] with dropped names removed from every reference list and each kept name the class index
     * maps recorded. The [DependencyRegistry] class index is released afterwards, whatever the outcome.
     */
    fun filter(result: StaticScanResult): StaticScanResult {
        try {
            dependencyListing.runOnce()
            when (dependencies.awaitListing(listingWait)) {
                DependencyRegistry.ListingOutcome.COMPLETE -> {
                    return keepReferences(result)
                }

                DependencyRegistry.ListingOutcome.FAILED -> {
                    log.log(
                        Level.INFO,
                        "otherlode: the static baseline is sent without references because the dependency listing failed, " +
                            "so there are no dependencies to map them to",
                    )
                }

                DependencyRegistry.ListingOutcome.TIMED_OUT -> {
                    log.log(
                        Level.WARNING,
                        "otherlode: the dependency listing had not finished after $listingWait; the static baseline is sent " +
                            "without references",
                    )
                }
            }
            return result.withoutReferences()
        } finally {
            dependencies.releaseClassIndex()
        }
    }

    private fun keepReferences(result: StaticScanResult): StaticScanResult {
        val kept = HashMap<String, Boolean>()
        val mappings = ArrayList<ExternalClass>()

        fun keep(names: List<String>): List<String> = names.filter { name -> kept.getOrPut(name) { resolve(name, result, mappings) } }

        return result.copy(
            declaredClasses =
                result.declaredClasses.map { declared ->
                    declared.copy(
                        methods = declared.methods.map { it.copy(referencedClasses = keep(it.referencedClasses)) },
                        referencedClasses = keep(declared.referencedClasses),
                    )
                },
            ownClassNames = emptySet(),
            flatJarClassNames = emptySet(),
            externalClasses = mappings,
        )
    }

    private fun resolve(
        name: String,
        result: StaticScanResult,
        mappings: MutableList<ExternalClass>,
    ): Boolean {
        if (providedByPlatform(name) || name in result.ownClassNames) return false
        val dependencyId = dependencies.dependencyForClass(name)
        if (dependencyId == null && name in result.flatJarClassNames) return false
        if (dependencyId != null) externalClasses.recordResolved(name, dependencyId)
        externalClasses.mappingFor(name)?.let(mappings::add)
        return true
    }

    companion object {
        val DEFAULT_LISTING_WAIT: Duration = Duration.ofMinutes(2)
    }
}
