package dev.otherlode.bootstrap;

import java.util.Map;

/**
 * Holds the JDK's table of application shutdown hooks from the moment the JVM starts them.
 *
 * <p>The agent weaves {@code java.lang.ApplicationShutdownHooks.runHooks} to call {@link
 * #offer(Map)} on entry with the table's {@code hooks} field. The JDK sets that field to null in
 * {@code runHooks} and adds no hook to the table after that, so the keys are the hooks the JVM
 * runs. {@link #hooks()} reads them from the agent's own shutdown hook.
 */
public final class OtherlodeShutdownHooks {
    private static volatile Map<Thread, Thread> table;

    private OtherlodeShutdownHooks() {}

    /** Keeps {@code hooks}, the JDK's table, or does nothing when it is null. Never throws. */
    public static void offer(Map<Thread, Thread> hooks) {
        if (hooks != null) {
            table = hooks;
        }
    }

    /**
     * Returns the hooks the JVM runs, or null when {@link #offer} has not been called since start or
     * the last {@link #forget}. Call it from a thread that the JVM started after {@link #offer}, such
     * as a shutdown hook.
     */
    public static Thread[] hooks() {
        Map<Thread, Thread> kept = table;
        return kept == null ? null : kept.keySet().toArray(new Thread[0]);
    }

    /** Forgets the table. */
    public static void forget() {
        table = null;
    }
}
