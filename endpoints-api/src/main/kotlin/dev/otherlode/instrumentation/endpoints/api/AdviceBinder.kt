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
 * Resolves an advice class by name, without loading it, from a combination of [agentClassLoader]
 * (where the advice class itself lives) and [targetClassLoader] (where the framework types the
 * advice references live).
 *
 * An advice class written against a framework's own types, such as Spring's `HandlerMethod` or
 * Ktor's `Route`, cannot be loaded in the classloader an [EndpointModule] implementation runs in:
 * those framework types are not on that classloader's classpath, so the advice class's own method
 * signatures would fail to resolve there. [Advice.to] only needs a
 * [net.bytebuddy.description.type.TypeDescription] and a way to read class bytes, neither of
 * which requires loading the class, so this reads the advice class's bytecode from
 * [agentClassLoader] and resolves any framework type its signature mentions from
 * [targetClassLoader] instead, through a [ClassFileLocator.Compound] of the two. Null
 * [targetClassLoader] means the bootstrap loader, resolved with [ClassFileLocator.ForClassLoader.ofBootLoader].
 * The resulting [Advice] is inlined directly into the framework class being instrumented, in the
 * classloader where the framework's own types do resolve. This is how OpenTelemetry's Java agent
 * references every advice class it ships: always by name, never as a class literal.
 *
 * A module attaches each hook through [hook], which records the hook's method matcher so the
 * pipeline can check that the matcher found a method to weave. The bare [bind] overloads are
 * `internal` so no hook can skip the record.
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

    /**
     * A binder that shares this one's locator and type pool and holds a record of its own. The
     * pipeline hands each module's `transform` call one, so the hooks it records belong to that
     * call alone while the cached binder stays shared across threads and modules.
     */
    fun forCall(): AdviceBinder = AdviceBinder(locator, typePool, mutableListOf())

    /** The hooks attached through [hook] on this binder, in the order they were attached. */
    fun recordedHooks(): List<RecordedHook> = record.toList()

    /**
     * Binds [adviceClassName] to every declared method [methodMatcher] accepts, and records the
     * pair so the pipeline can tell a hook that matched nothing. [remapPrefixes] is as for
     * [bind] with a prefix map.
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

    /** Reads and describes [adviceClassName]'s bytecode, ready to weave with [Advice.on]. */
    internal fun bind(adviceClassName: String): Advice = bind(adviceClassName, emptyMap())

    /**
     * Like [bind], but first rewrites every internal-name reference in [adviceClassName]'s own
     * bytecode whose prefix matches a key in [remapPrefixes] to that key's value, through ASM's
     * [ClassRemapper]. An empty map behaves exactly like the single-argument [bind].
     *
     * This is for an advice class written once against a library's unshaded names that also
     * needs to weave onto a relocated copy of the same library, such as the OpenTelemetry Java
     * agent's own shaded `instrumentation-api` classes. Rather than maintaining two advice
     * classes with identical logic, the same bytecode is rewritten at bind time: the original
     * class is read from [agentClassLoader][ClassFileLocator.ForClassLoader.of], remapped, and
     * described from a [ClassFileLocator.Compound] that offers the remapped bytes first and falls
     * back to the ordinary [locator] for every type the remapped bytecode references but does not
     * itself define, such as the relocated library type the rewritten reference now names.
     */
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
                // Weakly referenced on purpose: EndpointInstrumentation caches one binder per target
                // loader in a WeakHashMap keyed by that loader, and a strong reference from the value
                // back to its own key would keep the entry alive forever.
                if (targetClassLoader == null) {
                    ClassFileLocator.ForClassLoader.ofBootLoader()
                } else {
                    ClassFileLocator.ForClassLoader.WeaklyReferenced.of(targetClassLoader)
                },
            )
    }
}

/** A hook a module attached through [AdviceBinder.hook]: its advice class and the methods it targets. */
class RecordedHook(
    val adviceClassName: String,
    val methodMatcher: ElementMatcher<in MethodDescription>,
)

/**
 * Rewrites every internal-name reference in [originalBytes] whose prefix is a key in
 * [remapPrefixes], replacing that prefix with its value. Prefixes are tried longest first, so a
 * shorter prefix can never shadow a longer one it is itself a prefix of.
 *
 * `internal` so [AdviceBinderTest] can drive it directly with a small fixture class's bytes and
 * check the rewritten constant pool, without needing the real relocated library on the test
 * classpath to prove the rewrite happened.
 */
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
