package dev.otherlode.instrumentation.endpoints.api

import net.bytebuddy.asm.Advice
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.Remapper
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.pool.TypePool

/**
 * Binds advice classes by name without loading them. Class files are read from `agentClassLoader`
 * first, then from `targetClassLoader`, where null means the bootstrap loader. Each [hook] call is
 * recorded in [recordedHooks].
 */
class AdviceBinder private constructor(
    private val locator: ClassFileLocator,
    private val typePool: TypePool,
    private val record: MutableList<RecordedHook>,
) {
    constructor(agentClassLoader: ClassLoader, targetClassLoader: ClassLoader?) : this(
        locatorFor(agentClassLoader, targetClassLoader),
    )

    private constructor(locator: ClassFileLocator) : this(locator, TypePool.Default.of(locator), mutableListOf())

    /** A binder that shares this one's class file sources and type pool, with an empty record of its own. */
    fun forCall(): AdviceBinder = AdviceBinder(locator, typePool, mutableListOf())

    /** The hooks attached through [hook] on this binder, in the order they were attached. */
    fun recordedHooks(): List<RecordedHook> = record.toList()

    /**
     * Returns a wrapper that applies [adviceClassName] to the declared methods [methodMatcher] accepts,
     * and records the pair in [recordedHooks]. In the advice class, an internal name that starts with a
     * key of [remapPrefixes] has that prefix replaced by the key's value. The longest matching key wins.
     */
    fun hook(
        adviceClassName: String,
        methodMatcher: ElementMatcher<in MethodDescription>,
        remapPrefixes: Map<String, String> = emptyMap(),
    ): AsmVisitorWrapper.ForDeclaredMethods {
        val wrapper = bind(adviceClassName, remapPrefixes).on(methodMatcher)
        record += RecordedHook(adviceClassName, methodMatcher)
        return wrapper
    }

    internal fun bind(adviceClassName: String): Advice = bind(adviceClassName, emptyMap())

    internal fun bind(
        adviceClassName: String,
        remapPrefixes: Map<String, String>,
    ): Advice {
        if (remapPrefixes.isEmpty()) {
            return Advice.to(typePool.describe(adviceClassName).resolve(), locator)
        }
        val originalBytes = locator.locate(adviceClassName).resolve()
        val remappedBytes = remapClassBytes(originalBytes, remapPrefixes)
        val remappedLocator =
            ClassFileLocator.Compound(ClassFileLocator.Simple(mapOf(adviceClassName to remappedBytes)), locator)
        return Advice.to(
            TypePool.Default
                .of(remappedLocator)
                .describe(adviceClassName)
                .resolve(),
            remappedLocator,
        )
    }

    private companion object {
        fun locatorFor(
            agentClassLoader: ClassLoader,
            targetClassLoader: ClassLoader?,
        ): ClassFileLocator =
            ClassFileLocator.Compound(
                ClassFileLocator.ForClassLoader.of(agentClassLoader),
                // Must stay weakly referenced: the cached binder is a WeakHashMap value keyed by this
                // loader, and a strong reference would keep the entry alive forever.
                if (targetClassLoader == null) {
                    ClassFileLocator.ForClassLoader.ofBootLoader()
                } else {
                    ClassFileLocator.ForClassLoader.WeaklyReferenced.of(targetClassLoader)
                },
            )
    }
}

/** A hook attached through [AdviceBinder.hook]: its advice class and its method matcher. */
class RecordedHook(
    val adviceClassName: String,
    val methodMatcher: ElementMatcher<in MethodDescription>,
)

internal fun remapClassBytes(
    originalBytes: ByteArray,
    remapPrefixes: Map<String, String>,
): ByteArray {
    val orderedPrefixes = remapPrefixes.entries.sortedByDescending { it.key.length }
    val remapper =
        object : Remapper(Opcodes.ASM9) {
            override fun map(internalName: String): String {
                val match = orderedPrefixes.firstOrNull { (prefix, _) -> internalName.startsWith(prefix) } ?: return internalName
                return match.value + internalName.substring(match.key.length)
            }
        }
    val writer = ClassWriter(0)
    ClassReader(originalBytes).accept(ClassRemapper(writer, remapper), 0)
    return writer.toByteArray()
}
