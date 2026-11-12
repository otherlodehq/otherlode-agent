package dev.otherlode.instrumentation

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain

/**
 * Captures a class's bytes as they reach this agent's transformer chain, after every earlier
 * transformer, on a load, retransformation or redefinition. It must be registered
 * retransformation-capable immediately before ByteBuddy's own transformer. Bytes are kept per thread
 * and per class.
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
        if (className != null && isCandidate(className)) {
            recent.get()[className] = classfileBuffer
        }
        return null
    }

    /** Returns and removes the bytes captured for [internalName] on this thread, or null if none were. */
    fun take(internalName: String): ByteArray? = recent.get().remove(internalName)

    /** Returns the bytes captured for [internalName] on this thread without forgetting them, or null if none were. */
    fun peek(internalName: String): ByteArray? = recent.get()[internalName]

    private companion object {
        const val MAX_ENTRIES_PER_THREAD = 64
    }
}
