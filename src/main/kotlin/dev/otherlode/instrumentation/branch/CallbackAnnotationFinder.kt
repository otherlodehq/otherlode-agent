package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.CallbackAnnotations
import dev.otherlode.instrumentation.Inheritance
import dev.otherlode.instrumentation.Relation
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.utility.OpenedClassReader

/**
 * The annotations one method carries that the finder may count, as internal type names in
 * class-file order. [onMethod] holds the runtime-visible ones on the method. [classRetained] holds
 * the ones with class retention, recorded only when the adopter named an annotation (see
 * [AnnotationRecorder]). [onParameters] holds each runtime-visible parameter annotation with its
 * parameter index, in index order and then class-file order.
 */
internal class MethodAnnotations(
    val onMethod: List<String>,
    val onParameters: List<String>,
    val classRetained: List<String> = emptyList(),
) {
    /**
     * Whether the method or one of its parameters carries a runtime-visible annotation from a
     * `ws.rs` package. REST 4.0 section 3.6 then inherits no JAX-RS annotation for the method.
     */
    val carriesJaxRs: Boolean = onMethod.any { isJaxRsPackage(it) } || onParameters.any { isJaxRsPackage(it) }

    private companion object {
        fun isJaxRsPackage(internalName: String): Boolean =
            internalName.startsWith("jakarta/ws/rs/") || internalName.startsWith("javax/ws/rs/")
    }
}

/**
 * Finds the first callback annotation of a method, directly or through meta-annotations at any
 * depth (ADR 0064).
 *
 * A method's runtime-visible annotations are tried first, in class-file order, against
 * [CallbackAnnotations.inheritance] and the adopter's [configured] names. An annotation counts when it
 * is in either, or when its own type carries one. That type's class file is read through
 * [readBytes], and a type whose class file cannot be read counts only when it is listed or named
 * itself. Types in `java.lang.annotation` and `kotlin.annotation` are never read.
 *
 * A class-retention annotation leads only to a named annotation: a framework on the built-in list
 * reads annotations at run time and cannot see one. So the method's class-retention annotations are
 * tried next, against the configured names alone, and a walk follows a class-retention annotation
 * on an annotation type only when it is named. An annotation type whose `value` element is an array
 * of a named annotation counts as that annotation, since javac writes only the container when a
 * repeatable annotation is used twice (JLS 9.7.5). With no name configured, none of this applies.
 *
 * Then each parameter's annotations, runtime-visible only, are tried against
 * [CallbackAnnotations.onParameter], by name alone. A configured name never counts there. The
 * annotations in that list can sit only on a parameter, never on an annotation type, so no
 * annotation type can carry one and a walk could never find one.
 *
 * An annotation type's answer is a [Reach]: whether any listed or named annotation is reachable
 * from it, and over which supertype relations (ADR 0069) the reachable ones pass down. A composed
 * annotation reaching several takes every relation any of them allows. [inheritable] asks that
 * question of the annotations on a supertype method.
 *
 * Answers are cached in [cache] when there is one, and for this finder alone otherwise. An answer
 * is cached only for the annotation a question started from, whose walk visits everything reachable
 * from it once. An answer found inside another walk may have been cut short by a cycle or by a
 * type that walk had already visited.
 *
 * Which configured names were seen is recorded apart from the answer: every annotation on the
 * method and every type it carries is looked through once per agent, so a name behind an earlier
 * match still counts as seen.
 */
internal class CallbackAnnotationFinder(
    private val readBytes: (internalName: String) -> ByteArray?,
    private val cache: BranchSiteAnalyzer.CrossClassTableCache?,
    private val configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
) {
    private val own = HashMap<String, Int>()

    /**
     * The internal name of the first callback annotation in [annotations], or null. [onInterface]
     * says the method is an interface's own, or a Kotlin `$DefaultImpls` body of one, where an
     * annotation its framework reads on interface methods only counts too.
     */
    fun first(
        annotations: MethodAnnotations,
        onInterface: Boolean = false,
    ): String? {
        if (configured.isActive) noteNamed(annotations.onMethod, annotations.classRetained)
        return annotations.onMethod.firstOrNull { Reach.counts(reach(it, namedOnly = false), onInterface) }
            ?: annotations.classRetained.firstOrNull { Reach.counts(reach(it, namedOnly = true), onInterface) }
            ?: annotations.onParameters.firstOrNull { it in CallbackAnnotations.onParameter }
    }

    /** Whether the adopter named any annotation, which is when [noteNamed] has work to do. */
    val tracksNamed: Boolean
        get() = configured.isActive

    /**
     * Records the configured names found on a method's annotations, as [first] does, for a method
     * that is not the one being labelled. A no-op when no name is configured.
     */
    fun noteNamed(
        onMethod: List<String>,
        classRetained: List<String>,
    ) {
        if (!configured.isActive) return
        onMethod.forEach(::lookForNamed)
        classRetained.forEach(::lookForNamed)
    }

    /**
     * The first annotation of a supertype method, as written, that passes down over [relation]: the
     * runtime-visible ones in class-file order, then the class-retention ones against the configured
     * names alone. With [blockJaxRs], an annotation passes down only through a reached annotation
     * that is not a JAX-RS one.
     */
    fun inheritable(
        onMethod: List<String>,
        classRetained: List<String>,
        relation: Relation,
        blockJaxRs: Boolean,
    ): String? =
        onMethod.firstOrNull { Reach.passes(reach(it, namedOnly = false), relation, blockJaxRs) }
            ?: classRetained.firstOrNull { Reach.passes(reach(it, namedOnly = true), relation, blockJaxRs) }

    /** The [Reach] of [annotation]: what listed or named annotations it is or carries. */
    private fun reach(
        annotation: String,
        namedOnly: Boolean,
    ): Int {
        val direct = targetReach(annotation, namedOnly, viaMeta = false)
        if (direct != 0) return direct
        if (isSkipped(annotation)) return 0
        // A named-only answer can differ from the full one, so it is cached under its own key. No
        // internal name holds a ';'.
        val key = if (namedOnly) "$annotation;named" else annotation
        recall(key)?.let { return it }
        return reachThrough(annotation, namedOnly, hashSetOf(annotation)).also { remember(key, it) }
    }

    private fun reachThrough(
        annotation: String,
        namedOnly: Boolean,
        visited: MutableSet<String>,
    ): Int {
        var found = 0
        for (carried in carriedBy(annotation)) {
            val direct = targetReach(carried, namedOnly, viaMeta = true)
            found =
                found or
                when {
                    direct != 0 -> {
                        direct
                    }

                    // A listed annotation its framework reads only directly decides alone, and
                    // here it was reached through another one, so nothing below it is read.
                    CallbackAnnotations.inheritance[carried]?.direct == true || isSkipped(carried) -> {
                        0
                    }

                    else -> {
                        recall(if (namedOnly) "$carried;named" else carried)
                            ?: if (visited.add(carried)) reachThrough(carried, namedOnly, visited) else 0
                    }
                }
        }
        return found
    }

    /**
     * The [Reach] of [annotation] when it is itself a listed or named annotation, and 0 otherwise.
     * With [viaMeta] it was found on an annotation type, where a listed annotation whose framework
     * reads it directly is not seen.
     */
    private fun targetReach(
        annotation: String,
        namedOnly: Boolean,
        viaMeta: Boolean,
    ): Int {
        var found = 0
        if (configured.matches(annotation)) found = Reach.of(Inheritance.FROM_INTERFACES_AND_SUPERCLASSES, isJaxRs = false)
        if (!namedOnly) {
            CallbackAnnotations.inheritance[annotation]?.let {
                if (!(viaMeta && it.direct)) found = found or Reach.of(it, isJaxRs = annotation in CallbackAnnotations.jaxRs)
            }
        }
        return found
    }

    /**
     * Records every configured name that is [annotation] or that its type carries at any depth, by
     * the edges a walk follows, built-in types included, since an adopter may name an annotation a
     * framework's own carries. Each type is looked through once per agent, the first time any loader
     * meets it.
     */
    private fun lookForNamed(annotation: String) {
        if (!configured.firstVisit(annotation)) return
        configured.markIfNamed(annotation)
        if (isSkipped(annotation)) return
        carriedBy(annotation).forEach(::lookForNamed)
    }

    private fun recall(annotation: String): Int? = cache?.recallAnnotationAnswer(annotation) ?: own[annotation]

    private fun remember(
        annotation: String,
        answer: Int,
    ) {
        if (cache != null) cache.rememberAnnotationAnswer(annotation, answer) else own[annotation] = answer
    }

    /**
     * The annotation types on the annotation type [annotation], or none when it cannot be read. A
     * class-retention one is included only when it is a configured name, and so is the element type
     * of a `value` element that is an array of a configured name.
     */
    private fun carriedBy(annotation: String): List<String> =
        try {
            val bytes = readBytes(annotation)
            if (bytes == null) {
                emptyList()
            } else {
                val found = ArrayList<String>()
                OpenedClassReader.of(bytes).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            visible: Boolean,
                        ): AnnotationVisitor? {
                            internalNameOf(descriptor)?.let { if (visible || configured.matches(it)) found += it }
                            return null
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (name == "value") repeatedTypeOf(descriptor)?.let { if (configured.matches(it)) found += it }
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
                found
            }
        } catch (_: RuntimeException) {
            emptyList()
        }

    private fun isSkipped(internalName: String): Boolean =
        internalName.startsWith("java/lang/annotation/") || internalName.startsWith("kotlin/annotation/")

    /**
     * What an annotation type reaches, as bits. [COUNTS] is set when it is or carries a listed or
     * named annotation that counts on the method that carries it, and [COUNTS_ON_INTERFACE] when
     * one counts on an interface's own method, which also holds for an annotation its framework
     * reads on interface methods only. One read directly sets its bits only when the annotation type
     * asked about is that annotation. Bits 1 to 3 hold the [Relation]s over which a reached
     * annotation passes down, and bits 4 to 6 the same for reached annotations that are not JAX-RS.
     */
    internal object Reach {
        private const val COUNTS = 1

        /** Set beside [COUNTS], and alone for an annotation read on interface methods only. */
        private const val COUNTS_ON_INTERFACE = 1 shl 7

        /** Where the bits for [Relation]s over any family start. */
        private const val ANY_FAMILY_SHIFT = 1

        /** Where the bits for [Relation]s over non-JAX-RS families start. */
        private const val NON_JAX_RS_SHIFT = 4

        /** The reach of one listed or named annotation under [rule]. */
        fun of(
            rule: Inheritance,
            isJaxRs: Boolean,
        ): Int =
            rule.relations.fold(ownBits(rule)) { bits, relation ->
                bits or (1 shl (ANY_FAMILY_SHIFT + relation.ordinal)) or
                    nonJaxRs(relation, isJaxRs)
            }

        private fun nonJaxRs(
            relation: Relation,
            isJaxRs: Boolean,
        ): Int = if (isJaxRs) 0 else 1 shl (NON_JAX_RS_SHIFT + relation.ordinal)

        private fun ownBits(rule: Inheritance): Int =
            when {
                rule.countsOnMethod -> COUNTS or COUNTS_ON_INTERFACE
                Relation.INTERFACE_DEFAULT in rule.relations -> COUNTS_ON_INTERFACE
                else -> 0
            }

        /**
         * Whether [reach] says the annotation counts on the method that carries it, which is an
         * interface's own method, or a Kotlin `$DefaultImpls` body of one, when [onInterface].
         */
        fun counts(
            reach: Int,
            onInterface: Boolean,
        ): Boolean = reach and (if (onInterface) COUNTS_ON_INTERFACE else COUNTS) != 0

        /** Whether [reach] passes down over [relation], counting only non-JAX-RS annotations when [blockJaxRs]. */
        fun passes(
            reach: Int,
            relation: Relation,
            blockJaxRs: Boolean,
        ): Boolean = reach and (1 shl ((if (blockJaxRs) NON_JAX_RS_SHIFT else ANY_FAMILY_SHIFT) + relation.ordinal)) != 0
    }

    internal companion object {
        /** The element type of a no-argument method returning a one-dimensional object array, such as `()[Lcom/acme/Handles;`, or null. */
        private fun repeatedTypeOf(descriptor: String): String? =
            if (descriptor.startsWith("()[L") && descriptor.endsWith(";")) descriptor.substring(4, descriptor.length - 1) else null

        /** The internal name in an object type descriptor such as `Lcom/acme/Listener;`, or null for any other shape. */
        fun internalNameOf(descriptor: String): String? =
            if (descriptor.length > 2 && descriptor[0] == 'L' &&
                descriptor.last() == ';'
            ) {
                descriptor.substring(1, descriptor.length - 1)
            } else {
                null
            }
    }
}

/**
 * Records the annotations of the method it wraps and passes every event on to [delegate]. The
 * method's runtime-visible annotations are always recorded. Its class-retention ones are recorded
 * apart when [configured] names an annotation, and never otherwise. Parameter annotations are
 * runtime-visible only. [onEnd] receives them when the method ends.
 */
internal class AnnotationRecorder(
    delegate: MethodVisitor?,
    private val configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
    private val onEnd: (MethodAnnotations) -> Unit,
) : MethodVisitor(Opcodes.ASM9, delegate) {
    private val onMethod = ArrayList<String>()
    private val classRetained = ArrayList<String>()
    private val onParameters = ArrayList<Pair<Int, String>>()

    override fun visitAnnotation(
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        if (visible) {
            CallbackAnnotationFinder.internalNameOf(descriptor)?.let(onMethod::add)
        } else if (configured.isActive) {
            CallbackAnnotationFinder.internalNameOf(descriptor)?.let(classRetained::add)
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitParameterAnnotation(
        parameter: Int,
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        if (visible) CallbackAnnotationFinder.internalNameOf(descriptor)?.let { onParameters += parameter to it }
        return super.visitParameterAnnotation(parameter, descriptor, visible)
    }

    override fun visitEnd() {
        super.visitEnd()
        if (onMethod.isNotEmpty() || onParameters.isNotEmpty() || classRetained.isNotEmpty()) {
            onEnd(MethodAnnotations(onMethod, onParameters.sortedBy { it.first }.map { it.second }, classRetained))
        }
    }
}
