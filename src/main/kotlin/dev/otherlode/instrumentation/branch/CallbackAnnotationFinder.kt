package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.CallbackAnnotations
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
)

/**
 * Finds the first callback annotation of a method, directly or through meta-annotations at any
 * depth (ADR 0064).
 *
 * A method's runtime-visible annotations are tried first, in class-file order, against
 * [CallbackAnnotations.onMethod] and the adopter's [configured] names. An annotation counts when it
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
 * Answers are cached in [cache] when there is one, and for this finder alone otherwise. A true
 * answer is always cached. A false answer is cached only for the annotation a question started
 * from, since a false answer inside a cycle could turn true by another route.
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
    private val own = HashMap<String, Boolean>()

    /** The internal name of the first callback annotation in [annotations], or null. */
    fun first(annotations: MethodAnnotations): String? {
        if (configured.isActive) {
            annotations.onMethod.forEach(::lookForNamed)
            annotations.classRetained.forEach(::lookForNamed)
        }
        return annotations.onMethod.firstOrNull { counts(it, namedOnly = false) }
            ?: annotations.classRetained.firstOrNull { counts(it, namedOnly = true) }
            ?: annotations.onParameters.firstOrNull { it in CallbackAnnotations.onParameter }
    }

    private fun counts(
        annotation: String,
        namedOnly: Boolean,
    ): Boolean {
        if (isTarget(annotation, namedOnly)) return true
        if (isSkipped(annotation)) return false
        return carriesTarget(annotation, namedOnly, HashSet())
    }

    private fun carriesTarget(
        annotation: String,
        namedOnly: Boolean,
        visited: MutableSet<String>,
    ): Boolean {
        // A named-only answer can differ from the full one, so it is cached under its own key. No
        // internal name holds a ';'.
        val key = if (namedOnly) "$annotation;named" else annotation
        recall(key)?.let { return it }
        val isStart = visited.isEmpty()
        if (!visited.add(annotation)) return false
        val answer =
            carriedBy(annotation).any { carried ->
                isTarget(carried, namedOnly) || (!isSkipped(carried) && carriesTarget(carried, namedOnly, visited))
            }
        if (answer || isStart) remember(key, answer)
        return answer
    }

    private fun isTarget(
        annotation: String,
        namedOnly: Boolean,
    ): Boolean = configured.matches(annotation) || (!namedOnly && annotation in CallbackAnnotations.onMethod)

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

    private fun recall(annotation: String): Boolean? = cache?.recallAnnotationAnswer(annotation) ?: own[annotation]

    private fun remember(
        annotation: String,
        answer: Boolean,
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
