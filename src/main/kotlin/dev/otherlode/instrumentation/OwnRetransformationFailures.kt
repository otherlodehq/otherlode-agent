package dev.otherlode.instrumentation

import java.util.concurrent.ConcurrentHashMap

/**
 * The names of classes the agent itself failed to retransform. A failed retransformation makes
 * the JVM build a `VerifyError` that names a class that keeps running, and the agent drops a
 * `VerifyError` naming one of these, since that class is not one the verifier refused to link.
 */
class OwnRetransformationFailures {
    private val names = ConcurrentHashMap.newKeySet<String>()

    /** Records that retransforming [className], in dotted form, failed. */
    fun record(className: String) {
        names.add(className)
    }

    /** Whether the agent's own retransformation of [className] failed. */
    fun contains(className: String): Boolean = className in names
}
