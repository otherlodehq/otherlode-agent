package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.CallbackAnnotations
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.utility.OpenedClassReader

/**
 * The runtime-visible annotations one method carries, as internal type names in class-file order.
 * [onMethod] sits on the method. [onParameters] holds each parameter annotation with its parameter
 * index, in index order and then class-file order.
 */
internal class MethodAnnotations(
    val onMethod: List<String>,
    val onParameters: List<String>,
)

/**
 * Finds the first callback annotation of a method, directly or through meta-annotations at any
 * depth (ADR 0064).
 *
 * A method's own annotations are tried first, in class-file order, against
 * [CallbackAnnotations.onMethod]. An annotation counts when it is in that list, or when its own
 * type carries a listed annotation. That type's class file is read through [readBytes], and a type
 * whose class file cannot be read counts only when it is listed itself. Types in
 * `java.lang.annotation` and `kotlin.annotation` are never read.
 *
 * Then each parameter's annotations are tried against [CallbackAnnotations.onParameter], by name
 * alone. The annotations in that list can sit only on a parameter, never on an annotation type, so
 * no annotation type can carry one and a walk could never find one.
 *
 * Answers are cached in [cache] when there is one, and for this finder alone otherwise. A true
 * answer is always cached. A false answer is cached only for the annotation a question started
 * from, since a false answer inside a cycle could turn true by another route.
 */
internal class CallbackAnnotationFinder(
    private val readBytes: (internalName: String) -> ByteArray?,
    private val cache: BranchSiteAnalyzer.CrossClassTableCache?,
) {
    private val own = HashMap<String, Boolean>()

    /** The internal name of the first callback annotation in [annotations], or null. */
    fun first(annotations: MethodAnnotations): String? =
        annotations.onMethod.firstOrNull { counts(it) }
            ?: annotations.onParameters.firstOrNull { it in CallbackAnnotations.onParameter }

    private fun counts(annotation: String): Boolean {
        if (annotation in CallbackAnnotations.onMethod) return true
        if (isSkipped(annotation)) return false
        return carriesListed(annotation, HashSet())
    }

    private fun carriesListed(
        annotation: String,
        visited: MutableSet<String>,
    ): Boolean {
        recall(annotation)?.let { return it }
        val isStart = visited.isEmpty()
        if (!visited.add(annotation)) return false
        val answer =
            carriedBy(annotation).any { carried ->
                carried in CallbackAnnotations.onMethod || (!isSkipped(carried) && carriesListed(carried, visited))
            }
        if (answer || isStart) remember(annotation, answer)
        return answer
    }

    private fun recall(annotation: String): Boolean? = cache?.recallAnnotationAnswer(annotation) ?: own[annotation]

    private fun remember(
        annotation: String,
        answer: Boolean,
    ) {
        if (cache != null) cache.rememberAnnotationAnswer(annotation, answer) else own[annotation] = answer
    }

    /** The runtime-visible annotation types on the annotation type [annotation], or none when it cannot be read. */
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
                            if (visible) internalNameOf(descriptor)?.let(found::add)
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
 * Records the runtime-visible annotations of the method it wraps and passes every event on to
 * [delegate]. [onEnd] receives them when the method ends.
 */
internal class AnnotationRecorder(
    delegate: MethodVisitor?,
    private val onEnd: (MethodAnnotations) -> Unit,
) : MethodVisitor(Opcodes.ASM9, delegate) {
    private val onMethod = ArrayList<String>()
    private val onParameters = ArrayList<Pair<Int, String>>()

    override fun visitAnnotation(
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        if (visible) CallbackAnnotationFinder.internalNameOf(descriptor)?.let(onMethod::add)
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
        if (onMethod.isNotEmpty() || onParameters.isNotEmpty()) {
            onEnd(MethodAnnotations(onMethod, onParameters.sortedBy { it.first }.map { it.second }))
        }
    }
}
