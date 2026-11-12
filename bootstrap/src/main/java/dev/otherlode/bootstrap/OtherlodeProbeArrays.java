package dev.otherlode.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands each woven class its probe-count array from the installed {@link Resolver}.
 *
 * <p>A miss, with no resolver installed or no array of the requested size, returns a fresh array of
 * that size and logs a warning once per class name.
 */
public final class OtherlodeProbeArrays {
    /** Supplies a class's probe array. A null result, or one whose length is not {@code probeCount}, is a miss. */
    public interface Resolver {
        long[] resolve(String className, long layoutHash, int probeCount, ClassLoader classLoader);
    }

    private static volatile Resolver resolver;
    private static final Set<String> MISSED = ConcurrentHashMap.newKeySet();

    private OtherlodeProbeArrays() {
    }

    public static void install(Resolver newResolver) {
        resolver = newResolver;
    }

    /**
     * Bootstrap method for a dynamic constant that loads a probe array. {@code layoutHash} and
     * {@code probeCount} are its static arguments, and the array is resolved for the lookup class.
     * Returns as {@link #resolve} does.
     */
    public static long[] probeArray(
            MethodHandles.Lookup lookup, String name, Class<?> type, long layoutHash, int probeCount) {
        Class<?> woven = lookup.lookupClass();
        return resolve(woven.getName(), layoutHash, probeCount, woven.getClassLoader());
    }

    /**
     * Returns the probe array of {@code className}, or a fresh array of {@code probeCount} on a miss.
     * Never returns null. A throw from the resolver propagates.
     */
    public static long[] resolve(String className, long layoutHash, int probeCount, ClassLoader classLoader) {
        Resolver current = resolver;
        if (current != null) {
            long[] counts = current.resolve(className, layoutHash, probeCount, classLoader);
            if (counts != null && counts.length == probeCount) {
                return counts;
            }
        }
        if (MISSED.add(className)) {
            OtherlodeLoggers.get(OtherlodeProbeArrays.class.getName()).log(
                    Level.WARNING,
                    "otherlode: no registered probe array for " + className
                            + "; its hits will not be counted (resolver installed: " + (current != null) + ")");
        }
        return new long[probeCount];
    }
}
