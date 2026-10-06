package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.interned
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.utility.OpenedClassReader
import java.util.Arrays

/**
 * What the override walk needs of one class file: its supertype names and the methods another
 * class could override. Built from the header and method table alone, never from a method body.
 *
 * Names are internal, with slashes. A method is held as its name followed by its descriptor, such
 * as `run()V`. A static method, a private method, a constructor and `<clinit>` are left out, since
 * none of them can be overridden. A package-private method is held apart, since only a class in
 * the same package can override it.
 */
internal class TypeHeader private constructor(
    val isInterface: Boolean,
    val superName: String?,
    val interfaces: List<String>,
    private val visible: Array<String>,
    private val packagePrivate: Array<String>,
) {
    /**
     * Whether this type declares [key] as a method a class can override. A package-private method
     * counts only when [samePackage] is true.
     */
    fun declares(
        key: String,
        samePackage: Boolean,
    ): Boolean = Arrays.binarySearch(visible, key) >= 0 || (samePackage && Arrays.binarySearch(packagePrivate, key) >= 0)

    internal companion object {
        private val NONE = emptyArray<String>()

        /**
         * The header of the class file in [bytes], or null when they do not parse. A JDK type's
         * class file has the running JDK's version, so the reader accepts versions past the ASM
         * release ByteBuddy ships when ByteBuddy's experimental flag is on.
         */
        fun parse(bytes: ByteArray): TypeHeader? =
            try {
                val reader = OpenedClassReader.of(bytes)
                val visible = ArrayList<String>()
                val packagePrivate = ArrayList<String>()
                val visitor =
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (!canBeOverridden(access, name)) return null
                            val key = (name + descriptor).interned()
                            if (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0) visible += key else packagePrivate += key
                            return null
                        }
                    }
                reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                TypeHeader(
                    reader.access and Opcodes.ACC_INTERFACE != 0,
                    reader.superName?.interned(),
                    reader.interfaces.map { it.interned() },
                    sorted(visible),
                    sorted(packagePrivate),
                )
            } catch (_: RuntimeException) {
                null
            }

        /** Whether a method with [access] and [name] is one a subclass can override by name and descriptor. */
        fun canBeOverridden(
            access: Int,
            name: String,
        ): Boolean = access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0 && name != "<init>" && name != "<clinit>"

        private fun sorted(keys: List<String>): Array<String> = if (keys.isEmpty()) NONE else keys.toTypedArray().also { it.sort() }
    }
}

/**
 * Finds, for the methods of one class, the first out-of-scope supertype that declares a method
 * they override.
 *
 * The walk visits the superclass, then that type's own supertypes in the same order, then each
 * interface in declaration order with its supertypes. A type seen once is not visited again, which
 * also ends a cycle. A type in scope is walked through but never named. A type whose class file
 * [headerOf] cannot read ends its branch of the walk and names nothing.
 *
 * A generic override has no declaration of its own to match, since the descriptor it matches is
 * its bridge's. So a same-class bridge that matches passes its type to the method it calls.
 */
internal class OverrideWalk(
    private val includePackages: List<String>,
    private val excludePackages: List<String>,
    private val headerOf: (internalName: String) -> TypeHeader?,
) {
    private class Declaring(
        val internalName: String,
        val header: TypeHeader,
        val packageName: String,
    )

    /**
     * The out-of-scope type each method in [eligible] overrides, dotted and by (name, descriptor).
     * A method with no such type is absent. [methodAccess] holds every method the class declares.
     * [sameClassCallees] gives the other methods of its own class a bridge calls. A bridge may call
     * one of another name: kotlinc's `size()I` bridge calls `getSize()I`.
     *
     * In a class whose name ends in `$DefaultImpls`, a static method whose first parameter is the
     * outer type is read as a Kotlin default body; see [defaultImplsTypes]. Every other method takes
     * the ordinary walk.
     */
    fun overriddenTypes(
        internalClassName: String,
        superInternalName: String?,
        interfaceInternalNames: List<String>,
        methodAccess: Map<Pair<String, String>, Int>,
        eligible: Set<Pair<String, String>>,
        sameClassCallees: (Pair<String, String>) -> Set<Pair<String, String>>,
    ): Map<Pair<String, String>, String> {
        val declaring by lazy(LazyThreadSafetyMode.NONE) {
            outOfScopeSupertypes(
                internalClassName,
                listOfNotNull(superInternalName) + interfaceInternalNames,
            )
        }
        val packageName = packageOf(internalClassName)
        val result = HashMap<Pair<String, String>, String>()
        val bridges = ArrayList<Pair<Pair<String, String>, Pair<Int, Declaring>>>()
        for ((key, access) in methodAccess) {
            val isBridge = access and Opcodes.ACC_BRIDGE != 0
            if ((key !in eligible && !isBridge) || !TypeHeader.canBeOverridden(access, key.first)) continue
            val found = firstDeclaring(declaring, key, packageName) ?: continue
            if (isBridge) bridges += key to found else result[key] = found.second.internalName.replace('/', '.')
        }
        for ((bridge, found) in bridges.sortedBy { it.second.first }) {
            val target = sameClassCallees(bridge).singleOrNull() ?: continue
            val targetAccess = methodAccess[target] ?: continue
            if (target in eligible && TypeHeader.canBeOverridden(targetAccess, target.first)) {
                result.putIfAbsent(target, found.second.internalName.replace('/', '.'))
            }
        }
        if (internalClassName.endsWith(DEFAULT_IMPLS_SUFFIX)) {
            val defaultBodies = eligible.filterTo(HashSet()) { key -> (methodAccess[key] ?: 0) and Opcodes.ACC_STATIC != 0 }
            result.putAll(defaultImplsTypes(internalClassName, defaultBodies))
        }
        return result
    }

    /**
     * The types for a Kotlin `$DefaultImpls` class. Its method `m(LI;...)R` holds the default body of
     * the method `m(...)R` of interface `I`. It gets a type only when `I` declares that method and
     * it overrides an out-of-scope one. A default body that overrides a generic method, such as
     * `invoke(Ljava/lang/String;)V` for `Function1.invoke(Ljava/lang/Object;)Ljava/lang/Object;`,
     * is not marked: the interface holds no bridge to match, since each implementing class holds it.
     * Nor is a body whose name kotlinc changed, such as `getLength(LI;)I` for
     * `CharSequence.length()I`. A class whose outer type is not an interface is not a Kotlin
     * default-body class, so it gets nothing here.
     */
    private fun defaultImplsTypes(
        internalClassName: String,
        eligible: Set<Pair<String, String>>,
    ): Map<Pair<String, String>, String> {
        val interfaceName = internalClassName.removeSuffix(DEFAULT_IMPLS_SUFFIX)
        val receiver = "(L$interfaceName;"
        val candidates = eligible.filter { it.second.startsWith(receiver) }
        if (candidates.isEmpty()) return emptyMap()
        val header = headerOf(interfaceName)?.takeIf { it.isInterface } ?: return emptyMap()
        val declaring by lazy(LazyThreadSafetyMode.NONE) {
            outOfScopeSupertypes(interfaceName, listOfNotNull(header.superName) + header.interfaces)
        }
        val packageName = packageOf(interfaceName)
        val result = HashMap<Pair<String, String>, String>()
        for (key in candidates) {
            val interfaceKey = key.first + "(" + key.second.substring(receiver.length)
            if (!header.declares(interfaceKey, samePackage = true)) continue
            val found = declaring.firstOrNull { it.header.declares(interfaceKey, it.packageName == packageName) }
            if (found != null) result[key] = found.internalName.replace('/', '.')
        }
        return result
    }

    private fun firstDeclaring(
        declaring: List<Declaring>,
        key: Pair<String, String>,
        packageName: String,
    ): Pair<Int, Declaring>? {
        if (declaring.isEmpty()) return null
        val methodKey = key.first + key.second
        val index = declaring.indexOfFirst { it.header.declares(methodKey, it.packageName == packageName) }
        return if (index < 0) null else index to declaring[index]
    }

    /** The out-of-scope types in the walk's order, from [direct] the supertypes of [root]. */
    private fun outOfScopeSupertypes(
        root: String,
        direct: List<String>,
    ): List<Declaring> {
        val seen = hashSetOf(root)
        val found = ArrayList<Declaring>()

        fun visit(name: String) {
            if (!seen.add(name)) return
            val header = headerOf(name) ?: return
            if (!TypeMatchPolicy.isIncludedInternal(name, includePackages, excludePackages)) {
                found +=
                    Declaring(name, header, packageOf(name))
            }
            header.superName?.let(::visit)
            header.interfaces.forEach(::visit)
        }
        direct.forEach(::visit)
        return found
    }

    private fun packageOf(internalName: String): String = internalName.substringBeforeLast('/', "")

    private companion object {
        const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"
    }
}
