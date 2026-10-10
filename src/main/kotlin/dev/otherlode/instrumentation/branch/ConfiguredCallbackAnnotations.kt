package dev.otherlode.instrumentation.branch

import dev.otherlode.config.CallbackAnnotationName
import java.util.concurrent.ConcurrentHashMap

/**
 * The annotation types the adopter named in `callbackAnnotations`, and which of them a transform
 * has found on a method, directly or through the annotation types it carries (ADR 0064).
 *
 * [CallbackAnnotationFinder] asks [matches] while it decides a method's label and reports what it
 * finds through [markIfNamed], independently of that decision, so a name behind an earlier match
 * still counts as seen. [dev.otherlode.export.ExportScheduler] reads [unseen] once. Recording
 * happens at transform time and never on a probe hit. The agent shares one holder between every
 * finder and the scheduler.
 */
class ConfiguredCallbackAnnotations(
    names: List<CallbackAnnotationName>,
) {
    private val names: List<CallbackAnnotationName> = names.distinctBy { it.dotted }
    private val dottedNames: Set<String> = this.names.mapTo(HashSet()) { it.dotted }
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val visited: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether any name is configured, which is when class-retention annotations are read as well. */
    val isActive: Boolean = names.isNotEmpty()

    /** Whether [internalName], such as `com/acme/Bus$Handler`, is a configured name. */
    internal fun matches(internalName: String): Boolean = dottedNames.isNotEmpty() && dotted(internalName) in dottedNames

    /** Records [internalName] as seen when it is a configured name. */
    internal fun markIfNamed(internalName: String) {
        if (matches(internalName)) seen.add(dotted(internalName))
    }

    /**
     * Whether [internalName] is visited here for the first time. The finder looks through each
     * annotation type for configured names once per agent, whichever loader it was read from.
     */
    internal fun firstVisit(internalName: String): Boolean = visited.add(internalName)

    /** Records that the configured name [dottedName] was found on a method, as a transform would. */
    internal fun markSeen(dottedName: String) {
        seen.add(dottedName)
    }

    /** The configured names not yet seen, in the order the adopter wrote them. */
    fun unseen(): List<CallbackAnnotationName> = names.filter { it.dotted !in seen }

    private fun dotted(internalName: String): String = internalName.replace('/', '.').replace('$', '.')

    internal companion object {
        /** No names configured. */
        val NONE = ConfiguredCallbackAnnotations(emptyList())
    }
}
