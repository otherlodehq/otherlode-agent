package dev.otherlode.bootstrap;

import java.util.Map;

/** Keeps a table of application shutdown hooks passed to {@link #offer(Map)}, for {@link #hooks()} to read. */
public final class OtherlodeShutdownHooks {
    private static volatile Map<Thread, Thread> table;

    private OtherlodeShutdownHooks() {}

    /**
     * Keeps {@code hooks}, a table keyed by shutdown hook thread, in place of any kept before. Ignores
     * null. Never throws.
     */
    public static void offer(Map<Thread, Thread> hooks) {
        if (hooks != null) {
            table = hooks;
        }
    }

    /**
     * Returns the keys of the kept table, or null when {@link #offer} has kept none since start or the
     * last {@link #forget}. Reads the table without a lock, so call it once nothing else changes it.
     */
    public static Thread[] hooks() {
        Map<Thread, Thread> kept = table;
        return kept == null ? null : kept.keySet().toArray(new Thread[0]);
    }

    public static void forget() {
        table = null;
    }
}
