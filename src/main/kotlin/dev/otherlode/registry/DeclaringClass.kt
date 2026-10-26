package dev.otherlode.registry

import java.lang.ref.WeakReference

/**
 * The class whose transform declared endpoints, as [EndpointRegistry] needs it to watch whether
 * the JVM defines that class.
 *
 * [EndpointRegistry.register] takes one for each endpoint a module declares from inside a
 * transform. Every endpoint of one commit shares the same instance, so the registry starts one
 * definition attempt for the commit and not one for each endpoint.
 *
 * @property className The binary name of the class being transformed.
 */
class DeclaringClass internal constructor(
    val className: String,
    internal val loaderId: Int,
    internal val classLoaderRef: WeakReference<ClassLoader>?,
    internal val attempt: DefinitionAttempt?,
) {
    internal companion object {
        /**
         * The declaring class for [className] defined by [classLoader], which is null for the
         * bootstrap loader. [attempt] is null when the current thread is not inside the JVM's
         * transformer call, and the registry then never names the class as undefined.
         */
        fun of(
            className: String,
            classLoader: ClassLoader?,
            attempt: DefinitionAttempt?,
        ): DeclaringClass = DeclaringClass(className, System.identityHashCode(classLoader), classLoader?.let { WeakReference(it) }, attempt)
    }
}

/**
 * A declaring class that [EndpointRegistry.confirmFrom] has withheld for good, with the number of
 * its endpoints that no other source publishes.
 */
data class WithheldEndpointClass(
    val className: String,
    val endpointCount: Int,
)
