package dev.otherlode.instrumentation

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * Captures the received bytes: a class's bytes as they reach this agent's transformer chain, after
 * every earlier transformer.
 *
 * ByteBuddy's transform callback only exposes type metadata, not the buffer. The analysis reads the
 * class file, through the class's own loader, so an instance reports the same shape whatever ran
 * ahead of it; the probes are woven into the received bytes, so an earlier transformer's changes
 * survive. When the two differ, which is what JaCoCo's agent or AspectJ's weaver leaves,
 * [dev.otherlode.instrumentation.branch.SitePairing] lines up each method's branch sites between
 * them, and needs these bytes to do it. When the loader serves no class file for the class (bytes
 * defined from memory), these are the bytes the analysis reads.
 *
 * Registered with `canRetransform = false` immediately before ByteBuddy's own transformer, which
 * is registered the same way. The JVM calls transformers in registration order within that
 * group, so this one always sees a class just before ByteBuddy does, on the same thread. Bytes
 * are kept per thread in a small most-recently-used map rather than a single slot, because
 * resolving a type inside ByteBuddy's transform can trigger nested class loads (the agent's own
 * classes on first use, for example) whose transformer calls would otherwise overwrite the bytes
 * of the class still being transformed.
 */
class ClassBytesCapture(
    private val isCandidate: (internalName: String) -> Boolean,
) : ClassFileTransformer {
    private val recent =
        ThreadLocal.withInitial {
            object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_ENTRIES_PER_THREAD
            }
        }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className != null && classBeingRedefined == null && isCandidate(className)) {
            recent.get()[className] = classfileBuffer
        }
        return null
    }

    /** Returns and forgets the bytes captured for [internalName] on this thread, or null if none were. */
    fun take(internalName: String): ByteArray? = recent.get().remove(internalName)

    private companion object {
        /**
         * Bounds how many classes' bytes one thread can hold at once. Entries are consumed by the
         * transform that follows them, so this only ever holds candidates ByteBuddy declined to
         * transform (an unsafe annotation, no matching methods) until they age out.
         */
        const val MAX_ENTRIES_PER_THREAD = 64
    }
}
