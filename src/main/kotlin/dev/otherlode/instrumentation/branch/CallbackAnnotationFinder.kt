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

internal class MethodAnnotations(
    val onMethod: List<String>,
    val onParameters: List<String>,
    val classRetained: List<String> = emptyList(),
) {
    val carriesJaxRs: Boolean = onMethod.any { isJaxRsPackage(it) } || onParameters.any { isJaxRsPackage(it) }

    private companion object {
        fun isJaxRsPackage(internalName: String): Boolean =
            internalName.startsWith("jakarta/ws/rs/") || internalName.startsWith("javax/ws/rs/")
    }
}

internal class CallbackAnnotationFinder(
    private val readBytes: (internalName: String) -> ByteArray?,
    private val cache: BranchSiteAnalyzer.CrossClassTableCache?,
    private val configured: ConfiguredCallbackAnnotations = ConfiguredCallbackAnnotations.NONE,
) {
    private val own = HashMap<String, Int>()

    fun first(
        annotations: MethodAnnotations,
        onInterface: Boolean = false,
    ): String? {
        if (configured.isActive) noteNamed(annotations.onMethod, annotations.classRetained)
        return annotations.onMethod.firstOrNull { Reach.counts(reach(it, namedOnly = false), onInterface) }
            ?: annotations.classRetained.firstOrNull { Reach.counts(reach(it, namedOnly = true), onInterface) }
            ?: annotations.onParameters.firstOrNull { it in CallbackAnnotations.onParameter }
    }

    val tracksNamed: Boolean
        get() = configured.isActive

    fun noteNamed(
        onMethod: List<String>,
        classRetained: List<String>,
    ) {
        if (!configured.isActive) return
        onMethod.forEach(::lookForNamed)
        classRetained.forEach(::lookForNamed)
    }

    fun inheritable(
        onMethod: List<String>,
        classRetained: List<String>,
        relation: Relation,
        blockJaxRs: Boolean,
    ): String? =
        onMethod.firstOrNull { Reach.passes(reach(it, namedOnly = false), relation, blockJaxRs) }
            ?: classRetained.firstOrNull { Reach.passes(reach(it, namedOnly = true), relation, blockJaxRs) }

    private fun reach(
        annotation: String,
        namedOnly: Boolean,
    ): Int {
        val direct = targetReach(annotation, namedOnly, viaMeta = false)
        if (direct != 0) return direct
        if (isSkipped(annotation)) return 0
        // No internal name holds a ';', so the suffix cannot collide with an annotation.
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
        internalName.startsWith("java/lang/annotation/") || internalName.startsWith(KOTLIN_ANNOTATION_PREFIX)

    internal object Reach {
        private const val COUNTS = 1

        private const val COUNTS_ON_INTERFACE = 1 shl 7

        private const val ANY_FAMILY_SHIFT = 1

        private const val NON_JAX_RS_SHIFT = 4

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

        fun counts(
            reach: Int,
            onInterface: Boolean,
        ): Boolean = reach and (if (onInterface) COUNTS_ON_INTERFACE else COUNTS) != 0

        fun passes(
            reach: Int,
            relation: Relation,
            blockJaxRs: Boolean,
        ): Boolean = reach and (1 shl ((if (blockJaxRs) NON_JAX_RS_SHIFT else ANY_FAMILY_SHIFT) + relation.ordinal)) != 0
    }

    internal companion object {
        // Built at run time, since `shadowJar` rewrites a constant that starts with `kotlin`.
        private val KOTLIN_ANNOTATION_PREFIX = listOf("kot", "lin", "/annotation/").joinToString("")

        private fun repeatedTypeOf(descriptor: String): String? =
            if (descriptor.startsWith("()[L") && descriptor.endsWith(";")) descriptor.substring(4, descriptor.length - 1) else null

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
