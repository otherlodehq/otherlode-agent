package dev.otherlode.instrumentation.branch

import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ConstantDynamic
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor

internal class ReferenceCollector(
    private val names: MutableSet<String>,
    private val remembersDescriptors: Boolean = false,
) {
    fun internalName(name: String?) {
        if (name == null) return
        if (name.startsWith("[")) descriptor(name) else names += name
    }

    private var recent: Array<String?>? = null
    private var recentNext = 0

    fun descriptor(descriptor: String?) {
        if (descriptor == null) return
        if (remembersDescriptors) {
            val seen = recent ?: arrayOfNulls<String>(RECENT_DESCRIPTORS).also { recent = it }
            if (seen.any { it === descriptor }) return
            seen[recentNext] = descriptor
            recentNext = (recentNext + 1) % RECENT_DESCRIPTORS
        }
        var index = 0
        while (index < descriptor.length) {
            if (descriptor[index] == 'L') {
                val end = descriptor.indexOf(';', index)
                if (end < 0) return
                names += descriptor.substring(index + 1, end)
                index = end
            }
            index++
        }
    }

    fun type(type: Type) {
        when (type.sort) {
            Type.ARRAY -> {
                type(type.elementType)
            }

            Type.OBJECT -> {
                names += type.internalName
            }

            Type.METHOD -> {
                type.argumentTypes.forEach(::type)
                type(type.returnType)
            }
        }
    }

    fun signature(signature: String?) {
        if (signature == null) return
        try {
            SignatureReader(signature).accept(SignatureNames())
        } catch (_: RuntimeException) {
        }
    }

    fun constant(value: Any?) {
        when (value) {
            is Type -> {
                type(value)
            }

            is Handle -> {
                handle(value)
            }

            is ConstantDynamic -> {
                descriptor(value.descriptor)
                handle(value.bootstrapMethod)
                for (index in 0 until value.bootstrapMethodArgumentCount) constant(value.getBootstrapMethodArgument(index))
            }
        }
    }

    fun handle(handle: Handle) {
        internalName(handle.owner)
        descriptor(handle.desc)
    }

    fun annotation(
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        if (!visible) return null
        descriptor(descriptor)
        return AnnotationValues()
    }

    fun annotationDefault(): AnnotationVisitor = AnnotationValues()

    private inner class AnnotationValues : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(
            name: String?,
            value: Any?,
        ) {
            if (value is Type) type(value)
        }

        override fun visitEnum(
            name: String?,
            descriptor: String?,
            value: String?,
        ) {
            descriptor(descriptor)
        }

        override fun visitAnnotation(
            name: String?,
            descriptor: String?,
        ): AnnotationVisitor? = descriptor?.let { annotation(it, visible = true) }

        override fun visitArray(name: String?): AnnotationVisitor = this
    }

    private inner class SignatureNames : SignatureVisitor(Opcodes.ASM9) {
        private val open = ArrayDeque<String>()

        override fun visitClassType(name: String) {
            open.addLast(name)
            names += name
        }

        override fun visitInnerClassType(name: String) {
            val outer = open.removeLastOrNull() ?: return
            val inner = "$outer$$name"
            open.addLast(inner)
            names += inner
        }

        override fun visitEnd() {
            open.removeLastOrNull()
        }
    }

    private companion object {
        const val RECENT_DESCRIPTORS = 8
    }
}
