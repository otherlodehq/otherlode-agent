package dev.otherlode.instrumentation

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.pool.TypePool

/**
 * Refuses a class whose own supertype is absent. The JVM cannot define such a class, with the agent
 * or without it, so weaving one would publish probes for a class that never defines.
 *
 * The walk reads each supertype's class file through [classFileCache] for the class's loader and
 * takes the superclass and interface names from the class-file header, transitively, with a visited
 * set so a cycle ends. A type whose class file the loader does not serve, or whose bytes do not
 * parse, is missing; that includes a supertype defined at runtime with no class file, and every
 * supertype when the loader serves no class files at all. A name under `java.` is present without a
 * read: the JVM defines those from the boot or platform loader whatever the class's own loader says.
 */
internal class SupertypeGuard(
    private val classFileCache: ClassFileByteCache,
) {
    /**
     * The first missing type among the superclass and interfaces of [type], and of theirs in turn,
     * depth first, or null. [type]'s own supertypes are named by its description, which the weave
     * already holds; the rest come from class files read through [classLoader].
     */
    fun missingSupertype(
        type: TypeDescription,
        classLoader: ClassLoader?,
    ): String? {
        val locator = classFileCache.locatorFor(classLoader)
        val seen = hashSetOf(type.name)
        val supertypes = mutableListOf<String>()
        type.superClass?.let { supertypes += it.asErasure().name }
        type.interfaces.forEach { supertypes += it.asErasure().name }
        return firstMissing(supertypes, locator, seen)
    }

    private fun firstMissing(
        names: List<String>,
        locator: ClassFileLocator,
        seen: MutableSet<String>,
    ): String? {
        for (name in names) {
            if (name.startsWith(JAVA_PREFIX)) continue
            if (!seen.add(name)) continue
            val header = headerOf(name, locator) ?: return name
            firstMissing(header, locator, seen)?.let { return it }
        }
        return null
    }

    /** The supertype names in [name]'s class-file header, or null when the class file cannot be read. */
    private fun headerOf(
        name: String,
        locator: ClassFileLocator,
    ): List<String>? {
        val resolution = locator.locate(name)
        if (!resolution.isResolved) return null
        return try {
            val reader = ClassReader(resolution.resolve())
            (listOfNotNull(reader.superName) + reader.interfaces).map { it.replace('/', '.') }
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val JAVA_PREFIX = "java."
    }
}

/**
 * Why the transform of a class failed on purpose: the JVM cannot define it. [message] reads as a
 * predicate of the class: the listener logs it after the class name as one WARNING with no stack
 * trace, and records it as the class's skip reason.
 */
internal class SupertypeRefusal private constructor(
    message: String,
) : IllegalStateException(message) {
    internal companion object {
        /** The JVM cannot define the class: its supertype [supertype] could not be read from its loader. */
        fun undefinable(supertype: String) =
            SupertypeRefusal(
                "cannot be defined: its supertype $supertype could not be read from its loader, so it is not instrumented",
            )
    }
}
