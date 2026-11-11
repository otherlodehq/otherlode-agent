package dev.otherlode.instrumentation

import dev.otherlode.agentLogger
import dev.otherlode.config.AgentConfig
import dev.otherlode.dependencies.LoadedDependencyCounter
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.FrameworkMarkerRegistry
import dev.otherlode.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation

/**
 * Compares [Instrumentation.getAllLoadedClasses] with the registries on each [run]. It confirms
 * that registered classes were defined, withholds those the JVM never defined, and records loaded
 * classes the agent would have instrumented that reached no manifest. It also hands the array to
 * [dependencyCounter] and [frameworkMarkers] when they are given.
 */
open class LoadedClassSweep(
    private val instrumentation: Instrumentation,
    private val registry: ProbeRegistry,
    private val endpointRegistry: EndpointRegistry,
    private val config: AgentConfig,
    private val dependencyCounter: LoadedDependencyCounter? = null,
    private val frameworkMarkers: FrameworkMarkerRegistry? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log by agentLogger(LoadedClassSweep::class.java.name)
    private var everFoundUnreported = false

    /**
     * Runs the confirmation pass over every loaded class, and the unreported-class pass only when
     * [runForwardPass] is true. The array goes to [dependencyCounter], when given, even when a pass throws.
     * [final] logs a summary of the classes withheld for good, when there are any.
     */
    open fun run(
        runForwardPass: Boolean,
        final: Boolean = false,
    ) {
        // Read before the snapshot, so a class registered after the snapshot is not judged by it.
        val registeredUpTo = registry.registrationSequence()
        val endpointsRegisteredUpTo = endpointRegistry.registrationSequence()
        val loaded = instrumentation.allLoadedClasses
        try {
            frameworkMarkers?.observe(loaded, clock())
            confirm(loaded, registeredUpTo, endpointsRegisteredUpTo)
            if (runForwardPass) reportUnreported(loaded)
        } finally {
            dependencyCounter?.count(loaded)
        }
        if (final) logShutdownSummary()
    }

    private fun confirm(
        loaded: Array<Class<*>>,
        registeredUpTo: Long,
        endpointsRegisteredUpTo: Long,
    ) {
        val probesAwait = registry.unconfirmedClassCount() > 0
        val endpointsAwait = endpointRegistry.unconfirmedClassCount() > 0
        if (!probesAwait && !endpointsAwait) return
        val loadedNames = loaded.mapTo(mutableSetOf()) { it.name }
        if (probesAwait) {
            for (name in registry.confirmFrom(loadedNames, registeredUpTo)) {
                log.log(
                    Level.WARNING,
                    "otherlode: $name was woven and registered but the JVM never defined it; its probes are withheld for good",
                )
            }
        }
        if (endpointsAwait) {
            for (withheld in endpointRegistry.confirmFrom(loadedNames, endpointsRegisteredUpTo)) {
                log.log(
                    Level.WARNING,
                    "otherlode: ${withheld.className} declared ${endpointsPhrase(withheld.endpointCount)} while being woven " +
                        "but the JVM never defined it; its endpoints are withheld for good",
                )
            }
        }
    }

    private fun logShutdownSummary() {
        val withheld = registry.withheldForGoodClassCount()
        if (withheld > 0) {
            log.log(Level.WARNING, "otherlode: $withheld classes were woven and registered but never confirmed defined")
        }
        val withheldEndpointClasses = endpointRegistry.withheldForGoodClassCount()
        if (withheldEndpointClasses > 0) {
            log.log(
                Level.WARNING,
                "otherlode: $withheldEndpointClasses classes declared endpoints while being woven but were never confirmed defined",
            )
        }
    }

    private fun reportUnreported(loaded: Array<Class<*>>) {
        registry.purgeAccountedFor()
        val candidates = loaded.filter(::isCandidate).map { it.name }
        val unaccounted = registry.unaccountedFrom(candidates)
        val newlyFound = unaccounted.count { registry.recordUnreported(it) }
        if (newlyFound > 0) logFirstUnreportedFinding(newlyFound)
    }

    private fun logFirstUnreportedFinding(newlyFound: Int) {
        if (everFoundUnreported) return
        everFoundUnreported = true
        log.log(
            Level.INFO,
            "otherlode: $newlyFound loaded classes reached no transformer and are reported as unreported rather than " +
                "instrumented; a second agent in the chain is the usual cause",
        )
    }

    private fun isCandidate(loaded: Class<*>): Boolean {
        if (loaded.isArray || loaded.isPrimitive || loaded.isHidden) return false
        if (loaded.isSynthetic) return false
        val loader = loaded.classLoader ?: return false
        if (loader === ClassLoader.getPlatformClassLoader()) return false
        val name = loaded.name
        if (TypeMatchPolicy.isIgnoredName(name)) return false
        if (!TypeMatchPolicy.isIncluded(name, config.includePackages, config.excludePackages)) {
            return false
        }
        if (TypeMatchPolicy.isRuntimeGenerated(name)) return false
        return !isCoroutineContinuation(loaded)
    }

    private fun isCoroutineContinuation(loaded: Class<*>): Boolean {
        val superclassName = loaded.superclass?.name ?: return false
        return TypeMatchPolicy.CONTINUATION_SUPERCLASS_SUFFIXES.any { superclassName.endsWith(it) }
    }

    private fun endpointsPhrase(count: Int): String = if (count == 1) "1 endpoint" else "$count endpoints"
}
