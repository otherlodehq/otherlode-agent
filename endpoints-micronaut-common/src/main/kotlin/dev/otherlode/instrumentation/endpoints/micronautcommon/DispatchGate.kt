package dev.otherlode.instrumentation.endpoints.micronautcommon

import java.lang.ref.WeakReference

/**
 * Holds back a Micronaut router's routes until a dispatch point the module counts at has been
 * constructed in the process, then declares them. Either order declares.
 */
class DispatchGate(
    private val declareRoutes: (Any) -> Unit,
    private val notCounted: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Held(
        router: Any,
        val heldAt: Long,
    ) : WeakReference<Any>(router)

    private val lock = Any()
    private var armed = false
    private var off = false
    private var held = ArrayList<Held>()

    /**
     * Declares router [frameworkObject] at once when the gate is armed and holds it weakly
     * otherwise. [ARMED] arms the gate and declares the held routers. After the gate has switched
     * the module off, a router is dropped.
     */
    fun accept(frameworkObject: Any) {
        if (frameworkObject is String && frameworkObject == ARMED) {
            arm()
            return
        }
        val declareNow =
            synchronized(lock) {
                when {
                    off -> {
                        false
                    }

                    armed -> {
                        true
                    }

                    else -> {
                        held.removeIf { it.get() == null }
                        held.add(Held(frameworkObject, clock()))
                        false
                    }
                }
            }
        if (declareNow) declareRoutes(frameworkObject)
    }

    private fun arm() {
        val release =
            synchronized(lock) {
                if (off || armed) return
                armed = true
                val routers = held.mapNotNull { it.get() }
                held = ArrayList()
                routers
            }
        release.forEach(declareRoutes)
    }

    /**
     * Switches the module off through [notCounted] when no dispatch point has armed the gate and a
     * router has been held for [graceMillis], or is held at all on the [finalFlush].
     */
    fun beforeManifest(
        graceMillis: Long,
        finalFlush: Boolean,
    ) {
        val switchOff =
            synchronized(lock) {
                if (off || armed) return
                val now = clock()
                val anyHeld = held.any { it.get() != null && (finalFlush || now - it.heldAt >= graceMillis) }
                if (anyHeld) {
                    off = true
                    held = ArrayList()
                }
                anyHeld
            }
        if (switchOff) notCounted()
    }

    companion object {
        /** What the advice on a dispatch point's constructor hands to `OtherlodeEndpoints.declare`. */
        const val ARMED: String = "dispatch-point-constructed"
    }
}
