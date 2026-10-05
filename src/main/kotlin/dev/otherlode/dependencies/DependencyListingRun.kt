package dev.otherlode.dependencies

/**
 * Runs the startup dependency listing at most once, on the thread of whoever needs its result
 * first. The listing streams every nested jar of a fat jar, so it stays off `premain`; the first
 * flush and the static baseline scan both depend on it, and each calls [runOnce] before reading
 * what it fills.
 *
 * A caller that arrives while another thread is running the listing blocks until that run ends,
 * so a return from [runOnce] always means the listing has ended, however it ended.
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
