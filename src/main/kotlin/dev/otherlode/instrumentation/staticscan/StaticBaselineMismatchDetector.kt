package dev.otherlode.instrumentation.staticscan

import java.util.concurrent.ConcurrentHashMap

/**
 * Detects a class that registers dynamically without having been in the static baseline computed at
 * startup.
 */
class StaticBaselineMismatchDetector {
    /** Every class name the static scan saw, in any bucket. Null until the scan completes, so no comparison is possible before then. */
    @Volatile
    var knownClassNames: Set<String>? = null

    private val alreadyWarned = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [className] is found missing from [knownClassNames]; false every time after. */
    fun shouldWarnAbout(className: String): Boolean {
        val known = knownClassNames ?: return false
        if (className in known) return false
        return alreadyWarned.add(className)
    }
}
