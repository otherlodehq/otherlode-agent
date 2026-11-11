package dev.otherlode.instrumentation.endpoints

import dev.otherlode.agentLogger
import dev.otherlode.registry.DeclaringClass
import dev.otherlode.registry.DefinitionAttempt
import java.lang.System.Logger.Level

/**
 * Holds endpoint registrations a module made while ByteBuddy was rewriting a class, until the rewrite
 * is known to have succeeded. The slot is per thread, since ByteBuddy runs a class's transform and
 * result callbacks back to back on the loading thread.
 *
 * [begin] opens the slot for a declaring class and [discard] drops it. Each staged declaration
 * receives that class, so the registry can hold the endpoints back until the JVM has defined it.
 * When no transform is in flight, [stage] declines and the caller registers directly.
 */
class PendingDeclarations internal constructor() {
    private val log by agentLogger(PendingDeclarations::class.java.name)
    private val staged = ThreadLocal<Staging?>()

    private class Staging(
        val className: String,
    ) {
        val declarations: MutableList<(DeclaringClass) -> Unit> = mutableListOf()
    }

    /**
     * Holds [declaration] and returns true if this thread is inside a transform. Otherwise returns
     * false and the caller runs it. A committed [declaration] receives the class whose transform
     * staged it.
     */
    fun stage(declaration: (DeclaringClass) -> Unit): Boolean {
        val pending = staged.get() ?: return false
        pending.declarations += declaration
        return true
    }

    /**
     * Starts holding declarations on this thread for the class named [className], and returns how
     * many are held already. When this thread already holds a list, the call keeps it and reports
     * its size, which the caller keeps as the mark to [rollbackTo].
     */
    fun begin(className: String): Int {
        val pending = staged.get()
        if (pending != null) return pending.declarations.size
        staged.set(Staging(className))
        return 0
    }

    /** Drops everything staged past [mark], for a module that threw partway through declaring. */
    fun rollbackTo(mark: Int) {
        val pending = staged.get()?.declarations ?: return
        while (pending.size > mark) pending.removeAt(pending.size - 1)
    }

    internal fun commit(
        classLoader: ClassLoader?,
        captureAttempt: () -> DefinitionAttempt? = DefinitionAttempt.Companion::capture,
    ) {
        val staging = staged.get() ?: return
        staged.remove()
        if (staging.declarations.isEmpty()) return
        val declaring = DeclaringClass.of(staging.className, classLoader, captureAttempt())
        for (declaration in staging.declarations) {
            try {
                declaration(declaring)
            } catch (t: Throwable) {
                // A throw out of commit would reach ByteBuddy's error path, which discards the rewritten class.
                log.log(
                    Level.WARNING,
                    "otherlode: an endpoint a module declared during a transform could not be registered; the rest of " +
                        "that class's endpoints still apply, and this one is discovered by dispatch if it is served",
                    t,
                )
            }
        }
    }

    /** Drops what this thread staged without running any of it, and stops holding. */
    fun discard() {
        staged.remove()
    }

    /** How many declarations this thread is holding; for tests. */
    fun pendingCount(): Int = staged.get()?.declarations?.size ?: 0
}
