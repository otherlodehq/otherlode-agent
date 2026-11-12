package dev.otherlode.instrumentation.branch

import dev.otherlode.config.CallbackAnnotationName
import java.util.concurrent.ConcurrentHashMap

/**
 * The annotation types the adopter named in `callbackAnnotations`, and which of them a transform
 * has found on a method, directly or through the annotation types it carries.
 * Recording happens at transform time and never on a probe hit.
 */
class ConfiguredCallbackAnnotations(
    names: List<CallbackAnnotationName>,
) {
    private val names: List<CallbackAnnotationName> = names.distinctBy { it.dotted }
    private val dottedNames: Set<String> = this.names.mapTo(HashSet()) { it.dotted }
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val visited: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val isActive: Boolean = names.isNotEmpty()

    internal fun matches(internalName: String): Boolean = dottedNames.isNotEmpty() && dotted(internalName) in dottedNames

    internal fun markIfNamed(internalName: String) {
        if (matches(internalName)) seen.add(dotted(internalName))
    }

    internal fun firstVisit(internalName: String): Boolean = visited.add(internalName)

    internal fun markSeen(dottedName: String) {
        seen.add(dottedName)
    }

    /** The configured names not yet seen, in the order the adopter wrote them. */
    fun unseen(): List<CallbackAnnotationName> = names.filter { it.dotted !in seen }

    private fun dotted(internalName: String): String = internalName.replace('/', '.').replace('$', '.')

    internal companion object {
        val NONE = ConfiguredCallbackAnnotations(emptyList())
    }
}
