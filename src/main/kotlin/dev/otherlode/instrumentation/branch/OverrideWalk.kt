package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.CallbackAnnotations
import dev.otherlode.instrumentation.Relation
import dev.otherlode.instrumentation.TypeMatchPolicy
import dev.otherlode.instrumentation.interned
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.utility.OpenedClassReader
import java.util.Arrays
import java.util.IdentityHashMap

/**
 * An overridable method that carries annotations, as the walk reads it on a supertype. [onMethod]
 * holds its runtime-visible annotations and [classRetained] its class-retention ones, which are kept
 * only when the adopter named an annotation. Both are internal names in class-file order.
 */
internal class SupertypeMethod(
    val packagePrivate: Boolean,
    val isAbstract: Boolean,
    val onMethod: List<String>,
    val classRetained: List<String>,
)

/**
 * What the override walk needs of one class file: its supertype names, the methods another class
 * could override, and the annotations of those that carry any. Built from the header and method
 * table alone, never from a method body.
 *
 * Names are internal, with slashes. A method is held as its name followed by its descriptor, such
 * as `run()V`. A static method, a private method, a constructor and `<clinit>` are left out, since
 * none of them can be overridden. A package-private method is held apart, since only a class in
 * the same package can override it. A method with no annotations adds nothing beyond its key, and
 * a `java.*` type keeps no annotations, since none is a callback annotation. Of the type's own
 * annotations only one matters, so [isActivityInterface] is all that is kept of them.
 */
internal class TypeHeader private constructor(
    val isInterface: Boolean,
    val superName: String?,
    val interfaces: List<String>,
    val isActivityInterface: Boolean,
    private val visible: Array<String>,
    private val packagePrivate: Array<String>,
    private val annotated: Map<String, SupertypeMethod>,
) {
    /**
     * Whether this type declares [key] as a method a class can override. A package-private method
     * counts only when [samePackage] is true.
     */
    fun declares(
        key: String,
        samePackage: Boolean,
    ): Boolean = Arrays.binarySearch(visible, key) >= 0 || (samePackage && Arrays.binarySearch(packagePrivate, key) >= 0)

    /**
     * The annotated method this type declares under [key], or null when it declares none or declares
     * it without annotations. A package-private method counts only when [samePackage] is true.
     */
    fun annotatedMethod(
        key: String,
        samePackage: Boolean,
    ): SupertypeMethod? = annotated[key]?.takeIf { samePackage || !it.packagePrivate }

    internal companion object {
        private val NONE = emptyArray<String>()

        /** [names] interned, held as `emptyList()` or `listOf(x)` when it has no more than one. */
        private fun compact(names: List<String>): List<String> =
            when (names.size) {
                0 -> emptyList()
                1 -> listOf(names[0].interned())
                else -> names.map { it.interned() }
            }

        /**
         * The header of the class file in [bytes], or null when they do not parse. A JDK type's
         * class file has the running JDK's version, so the reader accepts versions past the ASM
         * release ByteBuddy ships when ByteBuddy's experimental flag is on. Class-retention method
         * annotations are kept only when [configured] names an annotation.
         */
        fun parse(
            bytes: ByteArray,
            configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
        ): TypeHeader? =
            try {
                val reader = OpenedClassReader.of(bytes)
                val visible = ArrayList<String>()
                val packagePrivate = ArrayList<String>()
                val annotated = HashMap<String, SupertypeMethod>()
                val readsAnnotations = !reader.className.startsWith("java/")
                // One method visitor serves every method of the parse: ASM visits methods one at a
                // time, and each ends with visitEnd before the next begins.
                var pendingKey = ""
                var pendingPackagePrivate = false
                var pendingAbstract = false
                val onMethod = ArrayList<String>()
                val classRetained = ArrayList<String>()
                val annotationsOfMethod =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            isVisible: Boolean,
                        ): AnnotationVisitor? {
                            CallbackAnnotationFinder.internalNameOf(descriptor)?.let {
                                if (isVisible) {
                                    onMethod += it
                                } else if (configured.isActive) {
                                    classRetained += it
                                }
                            }
                            return null
                        }

                        override fun visitEnd() {
                            if (onMethod.isNotEmpty() || classRetained.isNotEmpty()) {
                                annotated[pendingKey] =
                                    SupertypeMethod(pendingPackagePrivate, pendingAbstract, compact(onMethod), compact(classRetained))
                            }
                            onMethod.clear()
                            classRetained.clear()
                        }
                    }
                var isActivityInterface = false
                val visitor =
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            isVisible: Boolean,
                        ): AnnotationVisitor? {
                            if (isVisible &&
                                CallbackAnnotationFinder.internalNameOf(descriptor) == CallbackAnnotations.ACTIVITY_INTERFACE
                            ) {
                                isActivityInterface = true
                            }
                            return null
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (!canBeOverridden(access, name)) return null
                            val key = (name + descriptor).interned()
                            val isPackagePrivate = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0
                            if (isPackagePrivate) packagePrivate += key else visible += key
                            if (!readsAnnotations) return null
                            pendingKey = key
                            pendingPackagePrivate = isPackagePrivate
                            pendingAbstract = access and Opcodes.ACC_ABSTRACT != 0
                            return annotationsOfMethod
                        }
                    }
                reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                TypeHeader(
                    reader.access and Opcodes.ACC_INTERFACE != 0,
                    reader.superName?.interned(),
                    reader.interfaces.map { it.interned() },
                    isActivityInterface,
                    sorted(visible),
                    sorted(packagePrivate),
                    annotated.ifEmpty { emptyMap() },
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
 * they override, and the first supertype method they override that carries a callback annotation
 * their framework honours there.
 *
 * The walk visits the superclass, then that type's own supertypes in the same order, then each
 * interface in declaration order with its supertypes. A type seen once is not visited again, which
 * also ends a cycle. A type in scope is walked through but never named as an overridden type, though
 * its annotated methods can label. A type whose class file [headerOf] cannot read ends its branch
 * of the walk and names nothing.
 *
 * A generic override has no declaration of its own to match, since the descriptor it matches is
 * its bridge's. So a same-class bridge that matches passes its type to the method it calls.
 */
internal class OverrideWalk(
    private val includePackages: List<String>,
    private val excludePackages: List<String>,
    private val headerOf: (internalName: String) -> TypeHeader?,
) {
    private class Supertype(
        val internalName: String,
        val header: TypeHeader,
        val packageName: String,
        val inScope: Boolean,
    ) {
        fun relationOf(method: SupertypeMethod): Relation =
            when {
                !header.isInterface -> Relation.SUPERCLASS
                method.isAbstract -> Relation.INTERFACE_ABSTRACT
                else -> Relation.INTERFACE_DEFAULT
            }
    }

    private val supertypeLists = HashMap<String, List<Supertype>>()
    private val activityInterfaceSets = IdentityHashMap<List<Supertype>, Set<String>>()

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
        val bridges = ArrayList<Pair<Pair<String, String>, Pair<Int, Supertype>>>()
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
     * What a method reads of itself for [inheritedAnnotations]: its own annotations, and whether it
     * already has a label of its own, which an inherited one never replaces.
     */
    class OwnLabels(
        val annotationsOf: (Pair<String, String>) -> MethodAnnotations?,
        val isLabelled: (Pair<String, String>) -> Boolean,
    )

    /**
     * The callback annotation each method in [eligible] inherits, dotted and as written on the
     * supertype method, by (name, descriptor). A method that inherits none is absent.
     *
     * The walk is that of [overriddenTypes], through supertypes in scope or not, and a bridge passes
     * what it finds to the method it calls, as there. It stops at the first supertype method with
     * the method's name and descriptor whose annotations pass down over its [Relation]: a method
     * that matches but carries nothing that passes does not end it. Within one supertype method, a
     * passing annotation is tried before the activity rule: a method an interface marks with
     * `@ActivityInterface` (see [activityInterfaces]) is labelled by it. A method that carries a
     * `ws.rs` annotation inherits no JAX-RS annotation, whichever supertype. A `$DefaultImpls`
     * body is looked up through its interface's method, as in [overriddenTypes].
     *
     * When [isActivityInterface], the class is an interface carrying `@ActivityInterface`, and each
     * of its own overridable methods is labelled by it, its own label aside.
     *
     * A method that already has a label of its own ([OwnLabels.isLabelled]) inherits none. When the
     * adopter named annotations, the walk still goes through every supertype method it overrides,
     * since each named annotation on one counts as seen.
     */
    fun inheritedAnnotations(
        internalClassName: String,
        superInternalName: String?,
        interfaceInternalNames: List<String>,
        methodAccess: Map<Pair<String, String>, Int>,
        eligible: Set<Pair<String, String>>,
        own: OwnLabels,
        finder: CallbackAnnotationFinder,
        sameClassCallees: (Pair<String, String>) -> Set<Pair<String, String>>,
        isActivityInterface: Boolean = false,
    ): Map<Pair<String, String>, String> {
        val supertypes by lazy(LazyThreadSafetyMode.NONE) {
            supertypes(internalClassName, listOfNotNull(superInternalName) + interfaceInternalNames)
        }
        val packageName = packageOf(internalClassName)
        val result = HashMap<Pair<String, String>, String>()
        val bridges = ArrayList<Triple<Pair<String, String>, Int, String>>()
        for ((key, access) in methodAccess) {
            val isBridge = access and Opcodes.ACC_BRIDGE != 0
            if ((key !in eligible && !isBridge) || !TypeHeader.canBeOverridden(access, key.first)) continue
            val probed = if (isBridge) bridgeTarget(key, methodAccess, eligible, sameClassCallees) ?: continue else key
            if (own.isLabelled(probed) && !finder.tracksNamed) continue
            val found =
                inheritedFrom(
                    supertypes,
                    key.first + key.second,
                    packageName,
                    own.annotationsOf(probed)?.carriesJaxRs == true,
                    !own.isLabelled(probed),
                    finder,
                ) ?: continue
            if (isBridge) bridges += Triple(probed, found.first, found.second) else result[key] = found.second
        }
        for ((target, _, annotation) in bridges.sortedBy { it.second }) result.putIfAbsent(target, annotation)
        // An activity interface's own methods are activities too: an implementation that does not
        // override a default method runs it.
        if (isActivityInterface) {
            for ((key, access) in methodAccess) {
                if (key in eligible && TypeHeader.canBeOverridden(access, key.first) && !own.isLabelled(key)) {
                    result.putIfAbsent(key, ACTIVITY_INTERFACE_DOTTED)
                }
            }
        }
        if (internalClassName.endsWith(DEFAULT_IMPLS_SUFFIX)) {
            val defaultBodies = eligible.filterTo(HashSet()) { key -> (methodAccess[key] ?: 0) and Opcodes.ACC_STATIC != 0 }
            result.putAll(inheritedDefaultImpls(internalClassName, defaultBodies, own, finder))
        }
        return result
    }

    private fun bridgeTarget(
        bridge: Pair<String, String>,
        methodAccess: Map<Pair<String, String>, Int>,
        eligible: Set<Pair<String, String>>,
        sameClassCallees: (Pair<String, String>) -> Set<Pair<String, String>>,
    ): Pair<String, String>? {
        val target = sameClassCallees(bridge).singleOrNull() ?: return null
        val targetAccess = methodAccess[target] ?: return null
        return target.takeIf { it in eligible && TypeHeader.canBeOverridden(targetAccess, it.first) }
    }

    /**
     * The index of the supertype and the annotation of the first supertype method under [methodKey]
     * (name then descriptor) whose annotations pass down, or null. With [wantLabel] false the walk
     * only records named annotations.
     */
    private fun inheritedFrom(
        supertypes: List<Supertype>,
        methodKey: String,
        packageName: String,
        blockJaxRs: Boolean,
        wantLabel: Boolean,
        finder: CallbackAnnotationFinder,
    ): Pair<Int, String>? {
        var label: Pair<Int, String>? = null
        val activityInterfaces = if (wantLabel) activityInterfaces(supertypes) else emptySet()
        for ((index, supertype) in supertypes.withIndex()) {
            val samePackage = supertype.packageName == packageName
            val method = supertype.header.annotatedMethod(methodKey, samePackage)
            if (method != null) {
                finder.noteNamed(method.onMethod, method.classRetained)
                if (label == null && wantLabel) {
                    finder
                        .inheritable(method.onMethod, method.classRetained, supertype.relationOf(method), blockJaxRs)
                        ?.let { label = index to it.replace('/', '.') }
                }
            }
            if (label == null && supertype.internalName in activityInterfaces && supertype.header.declares(methodKey, samePackage)) {
                label = index to ACTIVITY_INTERFACE_DOTTED
            }
            if (!finder.tracksNamed && (label != null || !wantLabel)) break
        }
        return label
    }

    /**
     * The interfaces among [supertypes] whose methods are activities: those that carry
     * `@ActivityInterface` and every super-interface of one, at any depth. Computed once per list.
     */
    private fun activityInterfaces(supertypes: List<Supertype>): Set<String> =
        activityInterfaceSets.getOrPut(supertypes) {
            val annotated =
                supertypes.filter { it.header.isInterface && it.header.isActivityInterface }
            if (annotated.isEmpty()) return@getOrPut emptySet()
            val byName = supertypes.associateBy { it.internalName }
            val marked = HashSet<String>()
            val pending = ArrayDeque(annotated.map { it.internalName })
            while (pending.isNotEmpty()) {
                val name = pending.removeLast()
                if (marked.add(name)) byName[name]?.header?.interfaces?.let(pending::addAll)
            }
            marked
        }

    private fun inheritedDefaultImpls(
        internalClassName: String,
        eligible: Set<Pair<String, String>>,
        own: OwnLabels,
        finder: CallbackAnnotationFinder,
    ): Map<Pair<String, String>, String> {
        val interfaceName = internalClassName.removeSuffix(DEFAULT_IMPLS_SUFFIX)
        val receiver = "(L$interfaceName;"
        val candidates = eligible.filter { it.second.startsWith(receiver) }
        if (candidates.isEmpty()) return emptyMap()
        val header = headerOf(interfaceName)?.takeIf { it.isInterface } ?: return emptyMap()
        val supertypes = supertypes(interfaceName, listOfNotNull(header.superName) + header.interfaces)
        val packageName = packageOf(interfaceName)
        val result = HashMap<Pair<String, String>, String>()
        for (key in candidates) {
            val interfaceKey = key.first + "(" + key.second.substring(receiver.length)
            if (!header.declares(interfaceKey, samePackage = true)) continue
            if (own.isLabelled(key) && !finder.tracksNamed) continue
            val blockJaxRs = own.annotationsOf(key)?.carriesJaxRs == true
            val found = inheritedFrom(supertypes, interfaceKey, packageName, blockJaxRs, !own.isLabelled(key), finder)
            when {
                found != null -> result[key] = found.second
                header.isActivityInterface && !own.isLabelled(key) -> result[key] = ACTIVITY_INTERFACE_DOTTED
            }
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
        declaring: List<Supertype>,
        key: Pair<String, String>,
        packageName: String,
    ): Pair<Int, Supertype>? {
        if (declaring.isEmpty()) return null
        val methodKey = key.first + key.second
        val index = declaring.indexOfFirst { it.header.declares(methodKey, it.packageName == packageName) }
        return if (index < 0) null else index to declaring[index]
    }

    /** The out-of-scope types in the walk's order, from [direct] the supertypes of [root]. */
    private fun outOfScopeSupertypes(
        root: String,
        direct: List<String>,
    ): List<Supertype> = supertypes(root, direct).filter { !it.inScope }

    /**
     * Every supertype of [root] the walk reaches, in scope or not, in the walk's order, from
     * [direct] its direct supertypes. Kept for the life of the walk, since both questions ask it.
     */
    private fun supertypes(
        root: String,
        direct: List<String>,
    ): List<Supertype> =
        supertypeLists.getOrPut(root) {
            val seen = hashSetOf(root)
            val found = ArrayList<Supertype>()

            fun visit(name: String) {
                if (!seen.add(name)) return
                val header = headerOf(name) ?: return
                found +=
                    Supertype(name, header, packageOf(name), TypeMatchPolicy.isIncludedInternal(name, includePackages, excludePackages))
                header.superName?.let(::visit)
                header.interfaces.forEach(::visit)
            }
            direct.forEach(::visit)
            found
        }

    private fun packageOf(internalName: String): String = internalName.substringBeforeLast('/', "")

    private companion object {
        const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"

        private val ACTIVITY_INTERFACE_DOTTED = CallbackAnnotations.ACTIVITY_INTERFACE.replace('/', '.')
    }
}
