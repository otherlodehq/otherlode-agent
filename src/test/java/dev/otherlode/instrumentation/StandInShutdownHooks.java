package dev.otherlode.instrumentation;

import java.util.Collection;
import java.util.IdentityHashMap;

/** The shape of {@code java.lang.ApplicationShutdownHooks}, for a test to weave. */
public final class StandInShutdownHooks {
    private static IdentityHashMap<Thread, Thread> hooks = new IdentityHashMap<>();

    private StandInShutdownHooks() {}

    public static synchronized void reset() {
        hooks = new IdentityHashMap<>();
    }

    public static synchronized void add(Thread hook) {
        hooks.put(hook, hook);
    }

    public static void runHooks() {
        Collection<Thread> threads;
        synchronized (StandInShutdownHooks.class) {
            threads = hooks.keySet();
            hooks = null;
        }
        threads.size();
    }

    public static synchronized boolean tableCleared() {
        return hooks == null;
    }
}
