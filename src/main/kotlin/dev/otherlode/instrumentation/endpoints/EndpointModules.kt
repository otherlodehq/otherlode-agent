package dev.otherlode.instrumentation.endpoints

import dev.otherlode.agentLogger
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import java.lang.System.Logger.Level
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * Finds every [EndpointModule] with [ServiceLoader]. It logs and skips a provider that fails to
 * load, so one broken module does not hide the others.
 */
object EndpointModules {
    private val log by agentLogger(EndpointModules::class.java.name)

    /** The modules found on [classLoader], sorted by name. */
    fun discover(classLoader: ClassLoader = EndpointModules::class.java.classLoader): List<EndpointModule> {
        val modules = mutableListOf<EndpointModule>()
        val iterator = ServiceLoader.load(EndpointModule::class.java, classLoader).iterator()
        while (hasNextSafely(iterator)) {
            nextSafely(iterator)?.let { modules += it }
        }
        return modules.sortedBy { it.name }
    }

    private fun hasNextSafely(iterator: Iterator<EndpointModule>): Boolean =
        try {
            iterator.hasNext()
        } catch (e: ServiceConfigurationError) {
            log.log(Level.WARNING, "otherlode: endpoint module discovery stopped early", e)
            false
        }

    private fun nextSafely(iterator: Iterator<EndpointModule>): EndpointModule? =
        try {
            iterator.next()
        } catch (e: ServiceConfigurationError) {
            log.log(Level.WARNING, "otherlode: an endpoint module provider failed to load and will be skipped", e)
            null
        }
}
