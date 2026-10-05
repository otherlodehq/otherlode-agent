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

/**
 * The entry and omission probes, written as ASM events at the first instruction of a method. Both
 * leave the operand stack as they found it and use no local, label or jump, so a method's frames
 * and line numbers are the received ones (ADR 0061).
 *
 * An entry probe is `probes[slot]++`. An omission probe, at the top of a Kotlin `$default` method,
 * adds the caller's mask bit for each optional parameter to that parameter's slot, so a supplied
 * parameter adds zero. Both are the same non-atomic read-modify-write as every other probe (ADR 0003).
 */
internal object MethodProbes {
    /** Deepest operand stack either probe reaches: the array, the index, the loaded `long`, then a `long` or an `int` and a shift count. */
    const val PROBE_STACK = 6

    /** The first 32 optional parameters of a `$default` method are counted: one mask `int`. */
    private const val MASK_BITS = 32

    /** The wrapper that writes `probes[slot]++` at the entry of each method [slotBySignature] names. */
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

    /** The wrapper that writes the omission probes at the entry of each `$default` method [bindings] names. */
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

    /** The local variable slot of the parameter at [parameterIndex], counting `this` and two slots for a `long` or `double`. */
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
