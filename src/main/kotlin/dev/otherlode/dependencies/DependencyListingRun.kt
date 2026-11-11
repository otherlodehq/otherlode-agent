package dev.otherlode.dependencies

/**
 * Runs the startup dependency listing at most once, on the thread of the first caller of [runOnce].
 * A caller that arrives while another thread runs the listing blocks until that run ends, so a
 * return from [runOnce] means the listing has ended, however it ended.
 */
class DependencyListingRun(
    private val listing: () -> Unit,
) {
    private val lock = Any()

    @Volatile
    private var ran = false

    /** Runs the listing if no caller has, and otherwise waits for the run in progress. */
    fun runOnce() {
        if (ran) return
        synchronized(lock) {
            if (ran) return
            try {
                listing()
            } finally {
                ran = true
            }
        }
    }
}
