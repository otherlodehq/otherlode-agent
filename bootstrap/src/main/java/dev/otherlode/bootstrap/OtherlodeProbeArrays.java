package dev.otherlode.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands each instrumented class its probe-count array at class-initialisation time.
 *
 * <p>Lives in the bootstrap classloader, so a class defined by any loader at all, including an
 * isolated one that never delegates to the system loader, can call {@link #resolve} from its
 * woven {@code <clinit>} without a {@code NoClassDefFoundError}. The agent itself lives in the
 * system loader and cannot be referenced from here, so it plugs its registry in through
 * {@link #install(Resolver)} at premain.
 *
 * <p>A class reaches its array in one of two ways, by class-file version. From version 55 every
 * probe loads it as a dynamic constant bootstrapped by {@link #probeArray}, which resolves on first
 * use whatever state the class's initialisation is in. Below 55 the class keeps a static field its
 * {@code <clinit>} fills with {@link #resolve}, and a probe that finds the field still null calls
 * {@link #resolve} itself. A supertype's initializer can run a class's code before the class's own
 * {@code <clinit>} has, so the field is null for that window and a probe cannot assume otherwise.
 *
 * <p>A miss (no resolver installed yet, or the registry has no array for this class) returns a
 * fresh array of the requested size and logs once. An uncounted class is the worst case this
 * design allows; a null array or an undersized one would surface as an exception inside the
 * application's own methods, which this class never causes.
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
     * version 55 or later. The woven class names it with the layout hash and the probe count as
     * static arguments. The class being woven is the lookup class, so its name and loader are not
     * passed.
     *
     * <p>A dynamic constant resolves on first use and does not depend on the class's own
     * initializer, which is what lets a probe run while a supertype is still initialising. This
     * never returns null and never throws for an ordinary miss, since a bootstrap failure surfaces
     * as a {@code BootstrapMethodError} inside the application's own method.
     */
    public static long[] probeArray(
            MethodHandles.Lookup lookup, String name, Class<?> type, long layoutHash, int probeCount) {
        Class<?> woven = lookup.lookupClass();
        return resolve(woven.getName(), layoutHash, probeCount, woven.getClassLoader());
    }

    /**
     * Called from a class's {@code <clinit>} prelude and from its accessor's slow path, in a
     * class of version below 55. Every argument but {@code classLoader}, which the woven code
     * reads from the class itself, is a constant woven at transform time.
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
            System.getLogger(OtherlodeProbeArrays.class.getName()).log(
                    Level.WARNING,
                    "otherlode: no registered probe array for " + className
                            + "; its hits will not be counted (resolver installed: " + (current != null) + ")");
        }
        return new long[probeCount];
    }
}
