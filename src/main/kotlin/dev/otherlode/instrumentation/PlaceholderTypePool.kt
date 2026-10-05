package dev.otherlode.instrumentation

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.annotation.AnnotationList
import net.bytebuddy.description.annotation.AnnotationSource
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.RecordComponentDescription
import net.bytebuddy.description.type.RecordComponentList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.description.type.TypeList
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.RecordComponentVisitor
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.jar.asm.TypePath
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor
import net.bytebuddy.pool.TypePool
import net.bytebuddy.utility.AsmClassReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Tallies the classes woven while a type their classpath lacks was described as a placeholder, and
 * how many distinct types that was. [dev.otherlode.instrumentation.OtherlodeInstrumentation] records
 * once per class, at its first weave, and the export scheduler logs one line when a flush first
 * finds a class. A type used only as an annotation does not count: ByteBuddy's own pool drops an
 * annotation type it cannot resolve without failing, so a placeholder changes nothing there.
 */
class PlaceholderCounts {
    private val classes = AtomicLong(0)
    private val types = ConcurrentHashMap.newKeySet<String>()

    /**
     * Records one woven class that named the missing types in [typeNames]. A no-op for an empty set.
     * The types go in first, so a reader that finds a class always finds at least one type.
     */
    fun record(typeNames: Set<String>) {
        if (typeNames.isEmpty()) return
        types.addAll(typeNames)
        classes.incrementAndGet()
    }

    /** How many woven classes needed a placeholder. */
    fun classes(): Long = classes.get()

    /** How many distinct missing types were described as placeholders. */
    fun types(): Int = types.size
}

/**
 * The [AgentBuilder.PoolStrategy] of the method tier: a pool that describes a type it cannot locate
 * as a [Placeholder] instead of failing.
 *
 * ByteBuddy resolves the types a class names in a field or method signature while it builds the
 * instrumented type, where the JVM resolves them only on use. A class that names an optional
 * dependency absent from its classpath loads without it, and without this pool fails to weave.
 *
 * A placeholder describes a type and never stands in for one in a frame: the agent never computes a
 * frame (ADR 0061), so every frame in a woven method is the class file's own and the verifier loads
 * exactly the types it loads for the unwoven class.
 *
 * Each transform's pool has [parent] ask first, the JVM-wide [JdkTypePool] in the agent, so a `java.`
 * type is parsed once and is never a placeholder.
 *
 * A pool is made per transform and kept in a thread-local for the length of it, so [missingSupertype]
 * and [substituted] answer for the class being woven. The agent's own class-file reads, the static
 * scanner and reference resolution do not use it: for them, resolved still means present.
 */
internal class PlaceholderPoolStrategy(
    private val parent: TypePool = TypePool.Empty.INSTANCE,
) : AgentBuilder.PoolStrategy {
    private val current = ThreadLocal<LazyPlaceholderPool?>()

    override fun typePool(
        classFileLocator: ClassFileLocator,
        classLoader: ClassLoader?,
    ): TypePool = typePool(classFileLocator)

    override fun typePool(
        classFileLocator: ClassFileLocator,
        classLoader: ClassLoader?,
        name: String,
    ): TypePool = typePool(classFileLocator)

    private fun typePool(classFileLocator: ClassFileLocator): TypePool {
        val pool = LazyPlaceholderPool(classFileLocator, parent)
        current.set(pool)
        return TypePool.LazyFacade(pool)
    }

    /** The names the transform running on this thread described as placeholders so far. */
    fun substituted(): Set<String> =
        current
            .get()
            ?.created()
            ?.substituted()
            .orEmpty()

    /**
     * Notes the bytes of the class being woven for this transform's thread. [substantive] reads the
     * annotation types out of them, and only when something was substituted, so a class that names
     * nothing missing is not parsed for this.
     */
    fun noteAnnotationTypes(classBytes: ByteArray) {
        current.get()?.noteClassBytes(classBytes)
    }

    /**
     * The names this transform described as placeholders that ByteBuddy's own pool would have failed
     * on: [substituted] without the annotation types of the class being woven. A placeholder stands in
     * for an annotation type ByteBuddy's own pool drops without a word, so it changes nothing the weave
     * writes.
     */
    fun substantive(): Set<String> {
        val pool = current.get()?.created() ?: return emptySet()
        val substituted = pool.substituted()
        if (substituted.isEmpty()) return emptySet()
        val bytes = pool.classBytes ?: return substituted.toSet()
        return substituted - AnnotationTypeScanner.namesIn(bytes)
    }

    /**
     * The first missing type among the superclass and interfaces of [type], and of theirs in turn, or
     * null. The JVM cannot define a class whose own supertype is absent, with the agent or without it,
     * so weaving one would publish probes for a class that never defines.
     *
     * Checked by name against what the pool substituted, since the pool hands back lazy wrappers that
     * are never an instance of [Placeholder].
     */
    fun missingSupertype(type: TypeDescription): String? = missingSupertype(type, HashSet())

    private fun missingSupertype(
        type: TypeDescription,
        seen: MutableSet<String>,
    ): String? {
        if (!seen.add(type.name)) return null
        val supertypes = mutableListOf<TypeDescription>()
        type.superClass?.let { supertypes += it.asErasure() }
        type.interfaces.forEach { supertypes += it.asErasure() }
        for (supertype in supertypes) {
            // Reading a property resolves the lazy description, which is what substitutes a missing one.
            supertype.modifiers
            if (supertype.name in substituted()) return supertype.name
            missingSupertype(supertype, seen)?.let { return it }
        }
        return null
    }

    /** Drops the pool of the transform that just finished, so a thread does not hold its descriptions. */
    fun release() = current.remove()
}

/**
 * Stands in for a [PlaceholderPool] until something asks it to describe a type, since ByteBuddy asks
 * for a pool for every class it looks at and most of them are never described. The pool is built on
 * the first [describe]; [created] is null before that.
 */
internal class LazyPlaceholderPool(
    private val classFileLocator: ClassFileLocator,
    private val parent: TypePool = TypePool.Empty.INSTANCE,
) : TypePool {
    @Volatile
    private var pool: PlaceholderPool? = null

    @Volatile
    private var pendingClassBytes: ByteArray? = null

    /** The pool, or null when nothing has been described through this one. */
    fun created(): PlaceholderPool? = pool

    /** Notes the bytes of the class being woven, for the pool whether it exists yet or not. */
    fun noteClassBytes(classBytes: ByteArray) {
        pendingClassBytes = classBytes
        pool?.classBytes = classBytes
    }

    private fun pool(): PlaceholderPool =
        pool ?: synchronized(this) {
            pool ?: PlaceholderPool(classFileLocator, parent).also {
                it.classBytes = pendingClassBytes
                pool = it
            }
        }

    override fun describe(name: String): TypePool.Resolution = pool().describe(name)

    override fun clear() {
        pool?.clear()
    }
}

/**
 * A lazily resolving pool whose cache replaces an unresolved description with a [Placeholder].
 *
 * The hook is the cache provider, not `doResolve`: `WithLazyResolution.doResolve` registers whatever
 * `Default.doDescribe` found, an illegal resolution included, and `AbstractBase.describe` consults
 * the cache before anything else, so substituting at `register` covers both ways in.
 *
 * Each class the pool parses is kept, bytes and all, until the transform ends, so a placeholder can
 * ask how many type arguments the classes parsed so far give its name. The signatures are only read
 * when a placeholder asks, which is rare, so a transform that meets no missing type pays nothing but
 * holding the readers.
 */
internal class PlaceholderPool private constructor(
    private val cache: SubstitutingCache,
    classFileLocator: ClassFileLocator,
    parent: TypePool,
) : TypePool.Default.WithLazyResolution(cache, classFileLocator, ReaderMode.FAST, parent) {
    constructor(
        classFileLocator: ClassFileLocator,
        parent: TypePool = TypePool.Empty.INSTANCE,
    ) : this(SubstitutingCache(), classFileLocator, parent)

    /** The bytes of the class being woven; see [PlaceholderPoolStrategy.noteAnnotationTypes]. */
    @Volatile
    var classBytes: ByteArray? = null

    private val unscanned = ConcurrentLinkedQueue<AsmClassReader>()
    private val arity = HashMap<String, Int>()

    init {
        cache.pool = this
    }

    override fun doParse(classReader: AsmClassReader): TypeDescription {
        unscanned.add(classReader)
        return super.doParse(classReader)
    }

    /** The names this pool described as placeholders. */
    fun substituted(): Set<String> = cache.names

    /**
     * How many type arguments the classes parsed so far give [name], or zero when none does. ByteBuddy's
     * lazy parameterized types throw when a type has a different number of type variables than the
     * arguments a signature gives it, so a placeholder must report as many as its references use.
     */
    fun typeVariableCount(name: String): Int =
        synchronized(arity) {
            while (true) {
                val reader = unscanned.poll() ?: break
                reader.accept(ArityScanner(arity), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            arity[name] ?: 0
        }

    /** A simple cache that swaps a description it is handed for an absent type with a [Placeholder]. */
    class SubstitutingCache : TypePool.CacheProvider {
        private val delegate = TypePool.CacheProvider.Simple.withObjectType()
        val names: MutableSet<String> = ConcurrentHashMap.newKeySet()
        lateinit var pool: PlaceholderPool

        override fun find(name: String): TypePool.Resolution? = delegate.find(name)

        override fun register(
            name: String,
            resolution: TypePool.Resolution,
        ): TypePool.Resolution {
            if (resolution.isResolved) return delegate.register(name, resolution)
            names += name
            return delegate.register(name, TypePool.Resolution.Simple(Placeholder(name, pool)))
        }

        override fun clear() = delegate.clear()
    }
}

/** Collects the names of the types a class file names as annotations, and the enum and class values inside them. */
private object AnnotationTypeScanner {
    fun namesIn(classBytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        try {
            ClassReader(classBytes).accept(Collector(names), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (_: RuntimeException) {
            // Unreadable bytes name no annotation; the weave fails on them by itself.
        }
        return names
    }

    private fun add(
        names: MutableSet<String>,
        descriptor: String?,
    ) {
        var element = descriptor ?: return
        while (element.startsWith("[")) element = element.substring(1)
        if (element.startsWith("L") && element.endsWith(";")) names += element.substring(1, element.length - 1).replace('/', '.')
    }

    private class Values(
        private val names: MutableSet<String>,
    ) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(
            name: String?,
            value: Any?,
        ) {
            if (value is Type) add(names, value.descriptor)
        }

        override fun visitEnum(
            name: String?,
            descriptor: String?,
            value: String?,
        ) = add(names, descriptor)

        override fun visitAnnotation(
            name: String?,
            descriptor: String?,
        ): AnnotationVisitor {
            add(names, descriptor)
            return this
        }

        override fun visitArray(name: String?): AnnotationVisitor = this
    }

    private class Collector(
        private val names: MutableSet<String>,
    ) : ClassVisitor(Opcodes.ASM9) {
        private fun annotation(descriptor: String?): AnnotationVisitor {
            add(names, descriptor)
            return Values(names)
        }

        override fun visitAnnotation(
            descriptor: String?,
            visible: Boolean,
        ) = annotation(descriptor)

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ) = annotation(descriptor)

        override fun visitField(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            value: Any?,
        ): FieldVisitor =
            object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)
            }

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor =
            object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)

                override fun visitAnnotationDefault(): AnnotationVisitor = Values(names)

                override fun visitParameterAnnotation(
                    parameter: Int,
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)
            }

        override fun visitRecordComponent(
            name: String?,
            descriptor: String?,
            signature: String?,
        ): RecordComponentVisitor =
            object : RecordComponentVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String?,
                    visible: Boolean,
                ) = annotation(descriptor)
            }
    }
}

/**
 * Why the transform of a class failed on purpose: the JVM cannot define it, so weaving it would
 * publish probes for a class that never loads. [message] reads as a predicate of the class: the
 * listener logs it after the class name as one WARNING with no stack trace, and records it as the
 * class's skip reason.
 */
internal class PlaceholderRefusal private constructor(
    message: String,
) : IllegalStateException(message) {
    internal companion object {
        /** The JVM cannot define the class: its supertype [supertype] could not be read from its loader. */
        fun undefinable(supertype: String) =
            PlaceholderRefusal(
                "cannot be defined: its supertype $supertype could not be read from its loader, so it is not instrumented",
            )
    }
}

/**
 * Records, for each class named in a generic signature with type arguments, how many it takes. Reads
 * signatures only: the scan skips code, debug info and frames.
 */
private class ArityScanner(
    private val arity: MutableMap<String, Int>,
) : ClassVisitor(Opcodes.ASM9) {
    private fun scan(signature: String?) {
        if (signature == null || '<' !in signature) return
        try {
            SignatureReader(signature).accept(Reader())
        } catch (_: RuntimeException) {
            // A malformed signature names no arity; ByteBuddy meets it, if at all, on its own.
        }
    }

    private inner class Reader : SignatureVisitor(Opcodes.ASM9) {
        private var name: String? = null
        private var arguments = 0

        private fun flush() {
            val current = name ?: return
            if (arguments > 0) arity.putIfAbsent(current.replace('/', '.'), arguments)
        }

        override fun visitClassType(name: String) {
            this.name = name
            arguments = 0
        }

        override fun visitInnerClassType(name: String) {
            flush()
            this.name = this.name + "$" + name
            arguments = 0
        }

        override fun visitTypeArgument() {
            arguments++
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor {
            arguments++
            return Reader()
        }

        override fun visitEnd() {
            flush()
            name = null
        }

        override fun visitClassBound(): SignatureVisitor = Reader()

        override fun visitInterfaceBound(): SignatureVisitor = Reader()

        override fun visitSuperclass(): SignatureVisitor = Reader()

        override fun visitInterface(): SignatureVisitor = Reader()

        override fun visitParameterType(): SignatureVisitor = Reader()

        override fun visitReturnType(): SignatureVisitor = Reader()

        override fun visitExceptionType(): SignatureVisitor = Reader()

        override fun visitArrayType(): SignatureVisitor = Reader()
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) = scan(signature)

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        scan(signature)
        return null
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        scan(signature)
        return null
    }

    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?,
    ): RecordComponentVisitor? {
        scan(signature)
        return null
    }
}

/**
 * A type absent from the classpath, described as an empty, public, top-level class extending
 * `Object`, with as many type variables as the signatures parsed so far give it type arguments.
 * [TypeDescription.Latent] answers some accessors by throwing; each is answered as an empty class
 * would answer it.
 */
internal class Placeholder(
    name: String,
    private val pool: PlaceholderPool,
) : TypeDescription.Latent(name, Opcodes.ACC_PUBLIC, TypeDescription.Generic.OBJECT) {
    override fun getTypeVariables(): TypeList.Generic =
        TypeList.Generic.Explicit(
            List(pool.typeVariableCount(name)) {
                TypeDescription.Generic.OfTypeVariable.Symbolic("T$it", AnnotationSource.Empty.INSTANCE)
            },
        )

    override fun getEnclosingMethod(): MethodDescription.InDefinedShape? = null

    override fun getEnclosingType(): TypeDescription? = null

    override fun getDeclaredTypes(): TypeList = TypeList.Empty()

    override fun isAnonymousType(): Boolean = false

    override fun isLocalType(): Boolean = false

    override fun getDeclaredFields(): FieldList<FieldDescription.InDefinedShape> = FieldList.Empty()

    override fun getDeclaredMethods(): MethodList<MethodDescription.InDefinedShape> = MethodList.Empty()

    override fun getDeclaredAnnotations(): AnnotationList = AnnotationList.Empty()

    override fun getDeclaringType(): TypeDescription? = null

    override fun getNestHost(): TypeDescription = this

    override fun getNestMembers(): TypeList = TypeList.Explicit(this)

    override fun getRecordComponents(): RecordComponentList<RecordComponentDescription.InDefinedShape> = RecordComponentList.Empty()

    override fun isRecord(): Boolean = false

    override fun getPermittedSubtypes(): TypeList = TypeList.Empty()
}
