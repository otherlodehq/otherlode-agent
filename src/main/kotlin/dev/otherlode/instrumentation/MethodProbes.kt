package dev.otherlode.instrumentation

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.pool.TypePool

internal object MethodProbes {
    const val PROBE_STACK = 6

    private const val MASK_BITS = 32

    fun entry(
        form: ProbeArrayLoad,
        slotBySignature: Map<Pair<String, String>, Int>,
    ): AsmVisitorWrapper =
        wrapping { delegate ->
            object : ClassVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                    val slot = slotBySignature[name to descriptor]
                    return if (visitor == null || slot == null) visitor else EntryProbe(visitor, form, slot)
                }
            }
        }

    fun omission(
        form: ProbeArrayLoad,
        bindings: Map<Pair<String, String>, DefaultSiteBinding>,
    ): AsmVisitorWrapper =
        wrapping { delegate ->
            object : ClassVisitor(Opcodes.ASM9, delegate) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                    val binding = bindings[name to descriptor]
                    if (visitor == null || binding == null) return visitor
                    val maskLocal = maskLocal(access, descriptor, binding.maskParameterIndex)
                    return OmissionProbes(visitor, form, binding.base, binding.optionalBits, maskLocal)
                }
            }
        }

    private fun maskLocal(
        access: Int,
        descriptor: String,
        parameterIndex: Int,
    ): Int {
        var local = if (access and Opcodes.ACC_STATIC != 0) 0 else 1
        val parameters = Type.getArgumentTypes(descriptor)
        for (index in 0 until parameterIndex) local += parameters[index].size
        return local
    }

    private fun wrapping(visitor: (ClassVisitor) -> ClassVisitor): AsmVisitorWrapper =
        object : AsmVisitorWrapper.AbstractBase() {
            override fun wrap(
                instrumentedType: TypeDescription,
                classVisitor: ClassVisitor,
                implementationContext: Implementation.Context,
                typePool: TypePool,
                fields: FieldList<FieldDescription.InDefinedShape>,
                methods: MethodList<*>,
                writerFlags: Int,
                readerFlags: Int,
            ): ClassVisitor = visitor(classVisitor)
        }

    private open class ProbeAtEntry(
        delegate: MethodVisitor,
    ) : MethodVisitor(Opcodes.ASM9, delegate) {
        override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int,
        ) = super.visitMaxs(maxOf(maxStack, PROBE_STACK), maxLocals)
    }

    private class EntryProbe(
        delegate: MethodVisitor,
        private val form: ProbeArrayLoad,
        private val slot: Int,
    ) : ProbeAtEntry(delegate) {
        override fun visitCode() {
            super.visitCode()
            form.load(mv)
            ProbeArrayMembers.push(mv, slot)
            mv.visitInsn(Opcodes.DUP2)
            mv.visitInsn(Opcodes.LALOAD)
            mv.visitInsn(Opcodes.LCONST_1)
            mv.visitInsn(Opcodes.LADD)
            mv.visitInsn(Opcodes.LASTORE)
        }
    }

    private class OmissionProbes(
        delegate: MethodVisitor,
        private val form: ProbeArrayLoad,
        private val base: Int,
        private val optionalBits: Int,
        private val maskLocal: Int,
    ) : ProbeAtEntry(delegate) {
        override fun visitCode() {
            super.visitCode()
            var rank = 0
            for (bit in 0 until MASK_BITS) {
                if (optionalBits ushr bit and 1 == 0) continue
                form.load(mv)
                ProbeArrayMembers.push(mv, base + rank++)
                mv.visitInsn(Opcodes.DUP2)
                mv.visitInsn(Opcodes.LALOAD)
                mv.visitVarInsn(Opcodes.ILOAD, maskLocal)
                ProbeArrayMembers.push(mv, bit)
                mv.visitInsn(Opcodes.IUSHR)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IAND)
                mv.visitInsn(Opcodes.I2L)
                mv.visitInsn(Opcodes.LADD)
                mv.visitInsn(Opcodes.LASTORE)
            }
        }
    }
}
