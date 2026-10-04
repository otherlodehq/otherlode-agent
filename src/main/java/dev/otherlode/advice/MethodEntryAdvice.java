package dev.otherlode.advice;

import net.bytebuddy.asm.Advice;

/**
 * Inlined into every instrumented method's entry point.
 *
 * {@code probes} is the exact {@code long[]} the class's {@link dev.otherlode.registry.ProbeRegistry}
 * entry owns. The instrumenting code chooses how it is loaded: a dynamic constant for a class of
 * version 55 or later, a private accessor below that. See {@link ProbeArray}.
 *
 * {@code index} is a per-method constant, bound at weave time. Both are plain loads, so a hit is
 * one array store with no registry lookup.
 */
public class MethodEntryAdvice {

    public static final String PROBE_ARRAY_FIELD = "$otherlodeProbeCounts";

    /** Name of the private static synthetic accessor a class below version 55 loads its array through. */
    public static final String PROBE_ARRAY_ACCESSOR = "$otherlodeProbes";

    /** Name of the accessor's slow path, called while the field is still null. */
    public static final String PROBE_ARRAY_SLOW_PATH = "$otherlodeProbesResolve";

    /**
     * A field added only to bytes handed back for a refused re-weave of a class woven with a
     * dynamic constant, so the JVM rejects them as a schema change and the woven class keeps running.
     */
    public static final String REFUSAL_MARKER_FIELD = "$otherlodeRefused";

    @Advice.OnMethodEnter
    public static void onEnter(
            @ProbeArray long[] probes,
            @ProbeIndex int index) {
        probes[index]++;
    }
}
