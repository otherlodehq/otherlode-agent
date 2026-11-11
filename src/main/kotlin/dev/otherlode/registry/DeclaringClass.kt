package dev.otherlode.registry

import java.lang.ref.WeakReference

/**
 * The class whose transform declared endpoints, which [EndpointRegistry] watches for whether the
 * JVM defines it. Every endpoint of one commit shares the same instance.
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
        fun of(
            className: String,
            classLoader: ClassLoader?,
            attempt: DefinitionAttempt?,
        ): DeclaringClass = DeclaringClass(className, System.identityHashCode(classLoader), classLoader?.let { WeakReference(it) }, attempt)
    }
}

/** A declaring class that [EndpointRegistry.confirmFrom] withheld for good, with its count of endpoints no other source publishes. */
data class WithheldEndpointClass(
    val className: String,
    val endpointCount: Int,
)
