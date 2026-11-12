package dev.otherlode.instrumentation

import java.util.concurrent.ConcurrentHashMap

class OwnRetransformationFailures {
    private val names = ConcurrentHashMap.newKeySet<String>()

    /** Records that retransforming [className], in dotted form, failed. */
    fun record(className: String) {
        names.add(className)
    }

    fun contains(className: String): Boolean = className in names
}
