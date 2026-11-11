package dev.otherlode.instrumentation

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.pool.TypePool

internal class SupertypeGuard(
    private val classFileCache: ClassFileByteCache,
) {
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

internal class SupertypeRefusal private constructor(
    message: String,
    val logged: Boolean = false,
) : IllegalStateException(message) {
    internal companion object {
        fun undefinable(supertype: String) =
            SupertypeRefusal(
                "cannot be defined: its supertype $supertype could not be read from its loader, so it is not instrumented",
            )

        fun blindLoader(reason: String) = SupertypeRefusal(reason, logged = true)
    }
}
