package dev.otherlode.instrumentation

/**
 * FNV-1a over a class's method and branch-site signatures. Callers pass the signatures in probe
 * array slot order, so a reorder of the slots changes the hash.
 */
object ProbeLayoutHash {
    private const val OFFSET_BASIS = -3750763034362895579L
    private const val PRIME = 1099511628211L

    /** The layout entry of a class that declares a `<clinit>`: its entry probe and its returned and threw slots. */
    const val TYPE_INITIALIZER_ENTRY = "<clinit>()V#typeinit+outcomes"

    fun of(signatures: List<String>): Long {
        var hash = OFFSET_BASIS
        for (signature in signatures) {
            for (byte in signature.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= PRIME
            }
            hash = hash xor 0xffL
            hash *= PRIME
        }
        return hash
    }
}
