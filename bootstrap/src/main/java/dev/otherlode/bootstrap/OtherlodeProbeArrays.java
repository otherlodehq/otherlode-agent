package dev.otherlode.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands each instrumented class its probe-count array.
 *
 * <p>Lives in the bootstrap classloader, so woven code can call {@link #resolve} without a {@code
 * NoClassDefFoundError} from any class whose loader delegates {@code dev.otherlode.bootstrap} to
 * the boot loader. The agent plugs its registry in through {@link #install(Resolver)} at premain.
 *
 * <p>A miss (no resolver installed, or no array of the requested size for the class) returns a
 * fresh array of that size and logs once per class name.
 */
public final class OtherlodeProbeArrays {
    /** Implemented by the agent; looks the class's array up in its registry. May return null. */
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
     * Bootstrap method of the dynamic constant a probe loads its array from, in a class of
     * version 55 or later. The woven class passes the layout hash and the probe count as static
     * arguments, and the lookup class is the class being woven. This never returns null and never
     * throws for an ordinary miss.
     */
    public static long[] probeArray(
            MethodHandles.Lookup lookup, String name, Class<?> type, long layoutHash, int probeCount) {
        Class<?> woven = lookup.lookupClass();
        return resolve(woven.getName(), layoutHash, probeCount, woven.getClassLoader());
    }

    /**
     * Returns the probe array of {@code className}. Woven code in a class of version below 55 calls
     * this directly, with every argument but {@code classLoader} a constant woven at transform time.
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
